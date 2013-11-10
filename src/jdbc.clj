(ns jdbc
  ^{:author "Andrey Antukh",
    :doc "A Clojure interface to SQL databases via JDBC
be.niwi/jdbc provides a simple abstraction for java jdbc interfaces supporting
all crud (create, read update, delete) operations on a SQL database, along
with basic transaction support.

Basic DDL operations will be  also supported in near future (create table,
drop table, access to table metadata).

Maps are used to represent records, making it easy to store and retrieve
data. Results can be processed using any standard sequence operations.

Differences with `clojure.java.jdbc`:
- Connection management is explicit.
- Explicit resource management.
- Transaction management is explicit with rally nested
  transactions support (savepoints).
- Native support for various connection pools implementations.
- No more complexity than necesary for each available
  function. No transaction management for each function; if
  you need transaction in some code, wrap it using `with-transaction`
  macro.
- Much more examples of use this api ;) (project without documentation
  is project that does not exists)."}
  (:import (java.net URI)
           (java.sql BatchUpdateException DriverManager
                     PreparedStatement ResultSet SQLException Statement Types)
           (java.util Hashtable Map Properties)
           (javax.sql DataSource))
  (:use [slingshot.slingshot :only [throw+ try+]])
  (:require [clojure.string :as str])
  (:refer-clojure :exclude [resultset-seq])
  (:gen-class))

;; Private api and definitions

(defn- strip-jdbc [^String spec]
  (if (.startsWith spec "jdbc:")
    (.substring spec 5)
    spec))

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
      :subprotocol scheme
      :classname (get classnames scheme)}
     (if-let [user-info (.getUserInfo uri)]
             {:user (first (str/split user-info #":"))
              :password (second (str/split user-info #":"))}))))


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
  "Given a standard dbspec or dbspec with datasource (with connection pool),
  returns a new connection."
  [{:keys [factory connection-uri classname subprotocol subname
           datasource username password]
    :as db-spec}]
  (cond
    (string? db-spec)
    (get-connection (URI. (strip-jdbc db-spec)))

    (instance? URI db-spec)
    (get-connection (parse-properties-uri db-spec))

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

(defrecord Connection [connection in-transaction rollback-only])

(defrecord QueryResult [stmt rs results]
  java.lang.AutoCloseable
  (close [this]
    (.close rs)
    (.close stmt)))

(defn- execute-batch
  "Executes a batch of SQL commands and returns a sequence of update counts.
   (-2) indicates a single operation operating on an unknown number of rows.
   Specifically, Oracle returns that and we must call getUpdateCount() to get
   the actual number of rows affected. In general, operations return an array
   of update counts, so this may not be a general solution for Oracle..."
  [^Statement stmt]
  (let [result (.executeBatch stmt)]
    (if (and (= 1 (count result)) (= -2 (first result)))
      (list (.getUpdateCount stmt))
      (seq result))))


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
  "Creates context obtaining new connection to database
  using plain specs. Specs can be plain default specs or
  specs with datasource instance (connection pool).

  For more information about connection pools,
  see `jdbc.pool` namespace documentation.

  Example:

    (with-connection dbspec conn
      (do-something-with conn))"
  [dbspec bindname & body]
  `(let [~bindname (make-connection dbspec)]
     (with-open [realconn# (:connection ~bindname)]
       ~@body))

(defn call-in-transaction
  "Wrap function in one transaction. If current connection is already in
  transaction, it uses savepoints for this purpose. The availability of
  this feature depends on database support for it.

  Example:

  (with-connection dbspec conn
    (call-in-transaction conn (fn [] (execute! conn 'DROP TABLE foo;'))))

  For more idiomatic code, you should use `with-transaction` macro.
  "
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
  "Creates a context that evaluates in transaction (or nested transaction).
  This is a more idiomatic way to execute some database operations in
  atomic way.

  Example:

    (with-transaction conn
      (execute! conn 'DROP TABLE foo;')
      (execute! conn 'DROP TABLE bar;'))
  "
  [conn & body]
  `(let [func# (fn [] ~@body)]
     (apply call-in-transaction [~conn func#])))

(defn execute!
  "Run arbitrary number of raw sql commands such as: CREATE TABLE,
  DROP TABLE, etc... If your want transactions, you can wrap this
  call in transaction using `with-transaction` context block macro.

  Warning: not all database servers support ddl in transactions.

  Examples:

    ;; Without transactions
    (with-connection dbspec conn
      (execute! 'CREATE TABLE foo (id serial, name text);'))

    ;; In one transaction
    (with-connection dbspec conn
      (with-transaction conn
        (execute! conn 'CREATE TABLE foo (id serial, name text);')))
  "
  [conn & commands]
  (let [connection (:connection conn)]
    (with-open [stmt (.createStatement conn)]
      (dorun (map (fn [command]
                    (.addBatch stmt command)) commands))
      (execute-batch stmt))))

(defn execute-prepared!
  "Same as `execute!` function, but works with prepared statements
  instead with raw sql.

  With this you can execute multiple operations throught
  one call.

  Example:

    (with-connection dbspec conn
      (let [sql 'UPDATE TABLE foo SET x = ? WHERE y = ?;']
        (execute-prepared! sql [1 2] [2 3] [3 4])))

    This code should send this sql sentences:

      UPDATE TABLE foo SET x = 1 WHERE y = 2;
      UPDATE TABLE foo SET x = 2 WHERE y = 3;
      UPDATE TABLE foo SET x = 3 WHERE y = 4;
  "
  [conn sql & param-groups]
  (let [connection (:connection conn)]
    (with-open [stmt (.prepareStatement connection sql)]
      (doseq [param-group param-groups]
        (dorun (map-indexed #(.setObject stmt (inc %1) %2) params))
        (.addBatch stmt))
      (execute-batch stmt))))

(defn result-set-lazyseq
  "Function that wraps result in a lazy seq. This function
  is part of public api but can not be used directly (you should pass
  this function as parameter to `query` function).

  Required parameters:
    rs: ResultSet instance.

  Optional named parameters:
    :identifiers -> function that is applied for column name
                    when as-arrays? is false
    :as-arrays?  -> by default this function return a lazy seq of
                    records as map, but in certain circumstances you
                    need results as array. With this keywork parameter
                    you can set result as array instead map record.
  "
  [rs & {:keys [identifiers as-arrays?]
         :or {identifiers str/lower-case as-arrays? false}}]

  (let [metadata    (.getMetaData rs)
        idxs        (range 1 (inc (.getColumnCount metadata)))
        keys        (->> idxs
                         (map (fn [i] (.getColumnLabel metadata i)))
                         (map (comp keyword identifiers)))
        row-values  (fn [] (map #(.getObject rs %) idxs))
        records     (fn thisfn []
                      (when (.next rs)
                        (cons (zipmap keys (row-values)) (lazy-seq (thisfn)))))
        rows        (fn thisfn []
                      (when (.next rs)
                        (cons (vec (row-values)) (lazy-seq (thisfn)))))]
    (if as-arrays?
      (cons (vec keys) (rows))
      (records))))

(def result-set-vec
  "Function that evaluates a result into one clojure persistent
  vector. Accept same parameters as `result-set-lazyseq`."
  [& args]
  (vec (doall (apply result-set-lazyseq [args]))))

(defn query
  "Run query on the database. This function is used for make select
  queries and obtain results from database.

  This method returns an instance of QueryResult class that
  implements `java.lang.AutoCloseable` and should be used in
  `with-open` function context. Otherwise, also you can use
  `with-query` specially defined macro for same purpose.

  Example:

    (with-connection dbspec conn
      (with-open [query-result (query conn 'SELECT name FROM people WHERE id = ?' [1])]
        (doseq [row (:results query-result)]
          (println row))))
  "
  (^QueryResult [conn sql params]
   (apply query [conn sql params {}]))
  (^QueryResult [conn sql params {:keys [lazy?] :or {lazy? false} :as options}]
   (let [connection (:connection conn)
         stmt       (if (instance? PreparedStatement sql) sql
                      (.prepareStatement connection sql))]
     (dorun (map-indexed #(.setObject stmt (inc %1) %2) params))
     (let [rs (.executeQuery stmt)]
       (if lazy?
         (QueryResult. stmt rs (result-set-lazyseq rs))
         (QueryResult. stmt rs (result-set-vec rs)))))))

(defmacro with-query
  "Idiomatic dsl macro for `query` function that automatically closes
  all resources when context is reached.

  Example:

    (with-query conn results
      ['SELECT name FROM people WHERE id = ?' [1]]
      (doseq [row results]
        (println row)))
  "
  [conn bindname [query params] & body]
  `(with-open [rs# (query ~conn ~query ~params)]
     (let [~bindname (:results rs#)]
       ~@body)))

;; (defn insert!
;;   "Given a database connection, a table name and either maps representing rows
;;    perform an insert or multiple insert."
;;   [conn table & records])
