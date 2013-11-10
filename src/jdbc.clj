(ns jdbc
  ^{
    :author "Andrey Antukh",
    :doc "A Clojure interface to SQL databases via JDBC

be.niwi/jdbc provides a simple abstraction for java jdbc interfaces supporting
all crud (create, read update, delete) operations on a SQL database, along
with basic transaction support.

Basic DDL operations will be  also supported in near future (create table,
drop table, access to table metadata).

Maps are used to represent records, making it easy to store and retrieve
data. Results can be processed using any standard sequence operations."}
  (:import (java.net URI)
           (java.sql BatchUpdateException DriverManager
                     PreparedStatement ResultSet SQLException Statement Types)
           (java.util Hashtable Map Properties)
           (javax.sql DataSource))
  (:use [slingshot.slingshot :only [throw+]])
  (:refer-clojure :exclude [resultset-seq])
  (:gen-class))

;; Private api and definitions

(def ^{:private true :doc "Map of classnames to subprotocols"} classnames
  {"postgresql"     "org.postgresql.Driver"
   "mysql"          "com.mysql.jdbc.Driver"
   "sqlserver"      "com.microsoft.sqlserver.jdbc.SQLServerDriver"
   "jtds:sqlserver" "net.sourceforge.jtds.jdbc.Driver"
   "derby"          "org.apache.derby.jdbc.EmbeddedDriver"
   "hsqldb"         "org.hsqldb.jdbcDriver"
   "h2"             "org.h2.Driver"
   "sqlite"         "org.sqlite.JDBC"})

(defn- parse-properties-uri [^URI uri]
  (let [host (.getHost uri)
        port (if (pos? (.getPort uri)) (.getPort uri))
        path (.getPath uri)
        scheme (.getScheme uri)]
    (merge
     {:subname (if port
                 (str "//" host ":" port path)
                 (str "//" host path))
      :subprotocol (subprotocols scheme scheme)}
     (if-let [user-info (.getUserInfo uri)]
             {:user (first (str/split user-info #":"))
              :password (second (str/split user-info #":"))}))))

(def ^{:private true
       :doc "Map friendly :concurrency values to ResultSet constants."}
  result-set-concurrency
  {:read-only ResultSet/CONCUR_READ_ONLY
   :updatable ResultSet/CONCUR_UPDATABLE})

(def ^{:private true
       :doc "Map friendly :cursors values to ResultSet constants."}
  result-set-holdability
  {:hold ResultSet/HOLD_CURSORS_OVER_COMMIT
   :close ResultSet/CLOSE_CURSORS_AT_COMMIT})

(def ^{:private true
       :doc "Map friendly :type values to ResultSet constants."}
  result-set-type
  {:forward-only ResultSet/TYPE_FORWARD_ONLY
   :scroll-insensitive ResultSet/TYPE_SCROLL_INSENSITIVE
   :scroll-sensitive ResultSet/TYPE_SCROLL_SENSITIVE})

(defn- throw-non-rte
  "This ugliness makes it easier to catch SQLException objects
  rather than something wrapped in a RuntimeException which
  can really obscure your code when working with JDBC from
  Clojure... :("
  [^Throwable ex]
  (cond (instance? java.sql.SQLException ex) (throw ex)
        (and (instance? RuntimeException ex) (.getCause ex)) (throw-non-rte (.getCause ex))
        :else (throw ex)))

(defn- make-raw-connection
  [{:keys [factory connection-uri classname subprotocol subname
           datasource username password]
    :as db-spec}]
  (cond
    (instance? URI db-spec)
    (get-connection (parse-properties-uri db-spec))

    (string? db-spec)
    (get-connection (URI. (strip-jdbc db-spec)))

    factory
    (factory (dissoc db-spec :factory))

    connection-uri
    (DriverManager/getConnection connection-uri)

    (and subprotocol subname)
    (let [url (format "jdbc:%s:%s" subprotocol subname)
          etc (dissoc db-spec :classname :subprotocol :subname)
          classname (or classname (classnames subprotocol))]
      (clojure.lang.RT/loadClassForName classname)
      (DriverManager/getConnection url (as-properties etc)))

    (and datasource username password)
    (.getConnection datasource username password)

    datasource
    (.getConnection datasource)

    :else
    (let [msg (format "db-spec %s is missing a required parameter" db-spec)]
      (throw (IllegalArgumentException. msg)))))

(defrecord Connection
  [connection in-transaction rollback-only])

;; Public Api

(defn make-connection
  "Creates a connection to a database. db-spec is a map containing connection
  parameters. db-spec is a map containing values for one of the following
  parameter sets:

  Factory:
    :factory     (required) a function of one argument, a map of params
    (others)     (optional) passed to the factory function in a map

  DriverManager:
    :subprotocol (required) a String, the jdbc subprotocol
    :subname     (required) a String, the jdbc subname
    :classname   (optional) a String, the jdbc driver class name
    (others)     (optional) passed to the driver as properties.

  DataSource:
    :datasource  (required) a javax.sql.DataSource
    :username    (optional) a String
    :password    (optional) a String, required if :username is supplied

  JNDI:
    Not supported because it's shit!

  Raw:
    :connection-uri (required) a String
                 Passed directly to DriverManager/getConnection

  URI:
    Parsed JDBC connection string - see below

  String:
    subprotocol://user:password@host:post/subname
                 An optional prefix of jdbc: is allowed."
  ^java.sql.Connection
  [dbspec]
  (let [connection (apply make-raw-connection [dbspec])]
    (Connection. connection (atom false) (atom false))))

(defmacro with-connection
  "Creates context with obtaining new connection to database
  using plain specs. Plain specs can contain connection parameters
  or datasource instance (connection pool).

  Example:

    (with-connection dbspec conn
      (do-something-with conn))"
  [dbspec bindname & body]
  `(let [~bindname (make-connection dbspec)]
      ~@body))

(defn call-in-transaction
  "Wrap function in one transaction. If current connection is already in
  transaction, it uses savepoints for this purpose. The availability of
  this feature depends on database support for it."
  [conn func {:keys [savepoints] :or {savepoints true} :as opts}]
  (when (and @(:in-transaction conn) (not savepoints))
    (throw+ "Savepoints explicitly disabled."))
  (let [connection      (:connection conn)
        in-transaction  (:in-transaction conn)]
    (if in-transaction
      (let [savepoint (.setSavepoint connection)]
        (try+
          (func)
          (.releaseSavepoint savepoint)
          (catch Throwable t
            (.rollback connection savepoint)
            (throw-non-rte t))))
      (let [current-autocommit (.getAutoCommit connection)
            rollback-only      (:rollback-only conn)]
        (swap! in-transaction not)
        (.setAutoCommit connection false)
        (try+
          (func)
          (if @rollback-only
            (.rollback connection)
            (.commit connection))
          (catch Throwable t
            (.rollback connection)
            (throw-non-rte t))
          (finally
            (swap! in-transaction not)
            (.setAutoCommit connection current-autocommit)))))))

(defmacro with-transaction
  [conn opts & body]
  `(let [func# (fn [] ~@body)]
     (apply call-in-transaction [~conn func# ~opts])))
