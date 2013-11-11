(ns jdbc
  ^{:author "Andrey Antukh",
    :doc "Alternative implementation of jdbc wrapper for clojure.

## Introduction

clj.jdbc provides a simple abstraction for java jdbc interfaces supporting
all crud (create, read update, delete) operations on a SQL database, along
with basic transaction support.

Basic DDL operations will be  also supported in near future (create table,
drop table, access to table metadata).

Maps are used to represent records, making it easy to store and retrieve
data. Results can be processed using any standard sequence operations.

## Why one other jdbc wrapper?

- Connection management should be explicit. clj.jdbc has clear differentiation
  between connection and dbspec without uneccesary nesting controls and with explicit
  resource management (using `with-open` or other specific macros for it, see
  examples).

- clk.jdb has full support of all transaccions api, with ability to set database
  isolation level and use of nested transactions (savepoints).

  `with-transaction` macro works well with nested transactions using savepoints
  when it used as nested transaction. It ceates new transaction if no one transaction
  is active or savepoins in other case.

- clj.jdbc has native support for connection pools, having helpers for varios
  implementations (c3p0 and bonecp) for convert a plain dbspec to
  dbspec with datasource.

- clj.jdbc has simpler implementation than clojure.java.jdbc. It has no more complexity
  than necesary for each available function in public api.

  As example:

  - clojure.java.jdbc has logic for connection nestig because it hasn't have proper
    connection management. Functions like `create!` can receive plain dbspec or dbspec
    with crated connection. If dbspec with active connection is received, it should
    increment a nesting value (this prevents a close connection at finish). This is a
    good example of complexity introduced with improperly connection management.

    With clj.jdbc, all work with database should explicitly wrapped in connection
    context using `with-connection` macro. And each function like `create!` can
    suppose that always going to receive a connection instance, removing connection
    handling from all functions.

  - clojure.java.jdbc has repeated transaction handling on each crud method
    (insert!, drop!, etc...). With clj.jdbc, if you want that some code runs in a
    transaction, you should wrap it in a transaction context using
    `with-transaction` macro (see transactions section for more information).

- Much more examples of use this api ;) (project without documentation
  is project that does not exists).

## Dbspecs or database connection parameters

Usually, all documentation of any jvm languaje that explains jdbc, always suppose
that a reader comes from java and knowns well about jdbc. This documentation will
not make the same mistake.

jdbc is a default abstraction/interface for sql databases written in java. Is like
a python DB-API or any other abstraction in any languaje. Clojure as a guest language
on a jvm, is benefits of having a good and well tested abstraction.

`dbspec` is a simple clojure way to define database connection parameters that are
used to create a new database connection or create new datasource (connection pool).

This is a default aspect of one dbspec definition:

  {:classname \"org.postgresql.Driver\"
   :subprotocol \"postgresql\"
   :subname \"//localhost:5432/dbname\"
   :user \"username\"
   :password \"password\"}

- `:classname` can be omited and it automatically resolved from predefined list
   using `:subprotocol`. This is a class location of jdbc driver. Each driver has
   one, in this example is a path to a postgresql jdbc driver.
- `:user` and `:password` can be ommited if them are empty

Also, dbspec has other formats that finally parsed to a previously explained format.
As example you can pass a string containing a url with same data:

  \"postgresql://user:password@localhost:5432/dbname\"

And also, it has other format using datasource, but it explained in 'Connection pools'
section.
"}
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

(defn- as-str
  "Given a naming strategy and a keyword, return the keyword as a
   string per that naming strategy. Given (a naming strategy and)
   a string, return it as-is.
   A keyword of the form :x.y is treated as keywords :x and :y,
   both are turned into strings via the naming strategy and then
   joined back together so :x.y might become `x`.`y` if the naming
   strategy quotes identifiers with `."
  [f x]
  (if (instance? clojure.lang.Named x)
    (let [n (name x)
          i (.indexOf n (int \.))]
      (if (= -1 i)
        (f n)
        (str/join "." (map f (.split n "\\.")))))
    (str x)))

(defn- as-properties
  "Convert any seq of pairs to a java.utils.Properties instance.
   Uses as-str to convert both keys and values into strings."
  [m]
  (let [p (Properties.)]
    (doseq [[k v] m]
      (.setProperty p (as-str identity k) (as-str identity v)))
    p))

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
    (make-raw-connection (URI. (strip-jdbc db-spec)))

    (instance? URI db-spec)
    (make-raw-connection (parse-properties-uri db-spec))

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

(defrecord QueryResult [stmt rs data]
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


(defn- make-prepared-statement
  "Given connection and parametrized query as vector with first
  argument as string and other arguments as params, return a
  prepared statement.

  Example:

    (let [stmt (make-prepared-statement conn [\"SELECT foo FROM bar WHERE id = ?\" 1])]
      (println (instance? java.sql.PreparedStatement stmt)))
    ;; -> true
  "
  [conn sqlvec]
  {:pre [(instance? Connection conn)
         (vector? sqlvec)]}
  (let [connection  (:connection)
        sql         (first sqlvec)
        params      (rest sqlvec)
        stmt        (.prepareStatement connection sql)]
    (when (seq params)
      (dorun (map-indexed #(.setObject stmt (inc %1) %2) params)))
    stmt))

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
       ~@body)))

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
        (dorun (map-indexed #(.setObject stmt (inc %1) %2) param-group))
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

(defn result-set-vec
  "Function that evaluates a result into one clojure persistent
  vector. Accept same parameters as `result-set-lazyseq`."
  [& args]
  (vec (doall (apply result-set-lazyseq [args]))))

(defn make-query
  "Given a connection and paramatrized sql, execute a query and
  return a instance of QueryResult that works as stantard clojure
  map but implements a closable interface.

  This functions indents be a low level access for making queries
  and it delegate to a user the resource management. You should
  use `with-open` macro for store a result as example:

    (with-open [result (make-query conn [\"SELECT foo FROM bar WHERE id = ?\" 1])]
      (doseq [row (:data result)]
        (println row)))

  A QueryResult contains a these keys:

  - `:stmt` as PreparedStatement instance
  - `:rs` as ResultSet instance
  - `:data` as lazy seq of results.

  You can pass options on call `make-query` for make `:data` key as
  evaluated (not lazy) instead of lazy sequence:

    (with-open [result (make-query conn [\"SELECT foo FROM bar WHERE id = ?\" 1] {:lazy? false})]
      (doseq [row (:data result)]
        (println row)))

  NOTE: It strongly recommended not use this function directly and use a `with-query`
  macro that manage resources for you and return directly a seq instead of a
  QueryResult instance.
  "
  ([conn sql-with-params]
   (apply make-query [conn sql-with-params {}]))

  ([conn sql-with-params {:keys [lazy?] :or {lazy? false} :as options}]
   {:pre [(or (instance? PreparedStatement sql-with-params)
              (vector? sql-with-params))]}
   (let [connection (:connection conn)
         stmt       (cond
                      (instance? PreparedStatement sql-with-params)
                      sql-with-params

                      (vector? sql-with-params)
                      (make-prepared-statement conn sql-with-params)

                      (string? sql-with-params)
                      (make-prepared-statement conn [sql-with-params]))]
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
  [conn bindname sql-with-params & body]
  `(with-open [rs# (query ~conn ~sql-with-params)]
     (let [~bindname (:data rs#)]
       ~@body)))
