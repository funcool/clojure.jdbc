;; Copyright 2014-2015 Andrey Antukh <niwi@niwi.be>
;;
;; Licensed under the Apache License, Version 2.0 (the "License")
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns jdbc.core
  "Alternative implementation of jdbc wrapper for clojure."
  (:require [clojure.string :as str]
            [jdbc.types :as types]
            [jdbc.impl :as impl]
            [jdbc.proto :as proto]
            [jdbc.util.exceptions :refer [with-exception raise-exc]]
            [jdbc.util.resultset :refer [result-set->lazyseq result-set->vector]]
            [jdbc.transaction :as tx]
            [jdbc.constants :as constants])
  (:import java.sql.PreparedStatement
           java.sql.ResultSet))

(defn execute-statement!
  "Given a connection statement and paramgroups (can be empty)
  execute the prepared statement and return results from it.

  This is a low level interface and should be used with precaution. This
  function is used internally for execue raw sql such as CREATE/DROP
  table.

  Status: Alpha - Implementation and name of this method can change on
  next versions."
  [conn ^PreparedStatement stmt param-groups]
  (if-not (seq param-groups)
    (with-exception
      (seq [(.executeUpdate stmt)]))
    (let [set-parameter (fn [^long index value]
                          (proto/set-stmt-parameter! value conn stmt (inc index)))]
      (doseq [pgroup param-groups]
        (dorun (map-indexed set-parameter pgroup))
        (.addBatch stmt))
      (seq (.executeBatch stmt)))))

(defn connection
  "Creates a connection to a database. As parameter accepts:

  - dbspec map containing connection parameters
  - dbspec map containing a datasource (deprecated)
  - URI or string (interpreted as uri)
  - DataSource instance

  The dbspec map has this possible variants:

  Classic approach:
    :subprotocol -> (required) string that represents a vendor name (ex: postgresql)
    :subname -> (required) string that represents a database name (ex: test)
    (many others options that are pased directly as driver parameters)

  Pretty format:
    :vendor -> (required) string that represents a vendor name (ex: postgresql)
    :name -> (required) string that represents a database name (ex: test)
    :host -> (optional) string that represents a database hostname (default: 127.0.0.1)
    :port -> (optional) long number that represents a database port (default: driver default)
    (many others options that are pased directly as driver parameters)

  URI or String format:
    vendor://user:password@host:post/dbname?param1=value

  Additional options:
    :schema -> string that represents a schema name (default: nil)
    :read-only -> boolean for mark entire connection read only.
    :isolation-level -> keyword that represents a isolation level (:none, :read-committed,
                        :read-uncommitted, :repeatable-read, :serializable)

  Opions can be passed as part of dbspec map, or as optional second argument.
  For more details, see documentation."
  ([dbspec] (connection dbspec {}))
  ([dbspec options]
   (let [^java.sql.Connection conn (proto/connection dbspec)
         ^java.sql.DatabaseMetaData metadata (.getMetaData conn)
         options (merge
                  (when (map? dbspec)
                    (dissoc dbspec
                            :user :password :subprotocol :subname
                            :datasource :vendor :name :host :port))
                  options)]
     ;; Set readonly flag if it found on the options map
     (some->> (:read-only options)
              (.setReadOnly conn))

     ;; Set the concrete isolation level if it found
     ;; on the options map
     (some->> (:isolation-level options)
              (get constants/isolation-levels)
              (.setTransactionIsolation conn))

     ;; Set the schema if it found on the options map
     (some->> (:schema options)
              (.setSchema conn))
     (types/->connection conn))))

;; Backward compatibility alias.
(def make-connection connection)

(defn execute!
  "Run arbitrary number of raw sql commands such as: CREATE TABLE,
  DROP TABLE, etc... If your want transactions, you can wrap this
  call in transaction using `with-transaction` context block macro
  that is available in  `jdbc.transaction` namespace.

  Warning: not all database servers support ddl in transactions.

  Examples:

    ;; Without transactions
    (with-connection dbspec conn
      (execute! conn 'CREATE TABLE foo (id serial, name text);'))

    ;; In one transaction
    (with-connection dbspec conn
      (tx/with-transaction conn
        (execute! conn 'CREATE TABLE foo (id serial, name text);')))
  "
  [conn & commands]
  (let [^java.sql.Connection connection (proto/get-connection conn)]
    (with-open [stmt (.createStatement connection)]
      (dorun (map (fn [command]
                    (.addBatch stmt command)) commands))
      (seq (.executeBatch stmt)))))

(defn get-returning-records
  "Given a executed prepared statement with expected returning
  values. Return a vector of records of returning values.
  Usually is a id of just inserted objects, but in other cases
  can be complete objects."
  [conn ^PreparedStatement stmt]
  (let [rs (.getGeneratedKeys stmt)]
    (result-set->vector conn rs {})))

(defn is-prepared-statement?
  "Check if specified object is prepared statement."
  [obj]
  (instance? PreparedStatement obj))

(defn prepared-statement
  "Given a string or parametrized sql in sqlvec format
  return an instance of prepared statement."
  ([conn sqlvec] (prepared-statement conn sqlvec {}))
  ([conn sqlvec options] (proto/prepared-statement sqlvec conn options)))

(defn execute-prepared!
  "Given a active connection and sql (or prepared statement),
  executes a query in a database. This differs from `execute!` function
  with that this function allows pass parameters to query in a more safe
  way and permit pass group of parrams enabling bulk operations.

  After connection, sql/prepared statement and any number of group of
  params you can pass options map (same as on `make-prepared-statement`
  function).

  Note: Some options are incompatible with self defined prepared
  statement.

  Example:

  (with-connection dbspec conn
    (let [sql \"UPDATE TABLE foo SET x = ? WHERE y = ?;\"]
      (execute-prepared! conn sql [1 2] [2 3] [3 4])))
  "
  [conn sql & param-groups]
  ;; Try extract options from param-groups varargs. Options
  ;; should be a hash-map and located as the last parameter.
  ;; If any one know more efficient way to do it, pull-request
  ;; are welcome ;)
  (let [opts-candidate (last param-groups)
        options        (if (map? opts-candidate) opts-candidate {})
        param-groups   (if (map? opts-candidate)
                         (or (butlast param-groups) [])
                         param-groups)]

    ;; Check incompatible parameters.
    (when (and (:returning options) (is-prepared-statement? sql))
      (throw (IllegalArgumentException. "You can not pass prepared statement with returning options")))

    (when (and (seq param-groups) (vector? sql))
      (throw (IllegalArgumentException. "param-groups should be empty when sql parameter is a vector")))

    (cond
     ;; In case of sql is just a prepared statement
     ;; execute it in a standard way.
     (is-prepared-statement? sql)
     (execute-statement! conn sql param-groups)

     ;; In other case, build a prepared statement from sql or vector.
     (or (vector? sql) (string? sql))
     (with-open [^java.sql.PreparedStatement stmt (proto/prepared-statement sql conn options)]
       (let [res (execute-statement! conn stmt param-groups)]
         (if (:returning options)
           ;; In case of returning key is found on options
           ;; and it has logical true value, build special prepared
           ;; statement that expect return values.
           (get-returning-records conn stmt)
           res))))))

(defn query
  "Perform a simple sql query and return a evaluated result as vector.

  `sqlvec` parameter can be: parametrized sql (vector format), plain sql
  (simple sql string) or prepared statement instance.

  Example using parametrized sql:

    (doseq [row (query conn [\"SELECT foo FROM bar WHERE id = ?\" 1])]
      (println row))

  Example using plain sql (without parameters):

    (doseq [row (query conn \"SELECT version();\")]
      (println row))

  Example using extern prepared statement:

    (let [stmt (make-prepared-statement conn [\"SELECT foo FROM bar WHERE id = ?\" 1])]
      (doseq [row (query conn stmt)]
        (println row)))
  "
  ([conn sqlvec] (query conn sqlvec {}))
  ([conn sqlvec options]
   (let [^java.sql.PreparedStatement stmt (proto/prepared-statement sqlvec conn options)]
     (let [^ResultSet rs (.executeQuery stmt)]
       (result-set->vector conn rs options)))))

(def query-first
  "Perform a simple sql query and return the first result. It accepts the
  same arguments as the `query` function."
  (comp first query))

(defn lazy-query
  "Perform a lazy query using server side cursors if them are available.

  This function returns a cursor instance. That cursor allows create
  arbitrary number of lazyseqs.

  Some databases requires that this funcion and lazyseq iteration
  should be used in a transactoion context.

  The returned cursor should be used with `with-open` clojure function
  for proper resource handling."
  ([conn sqlvec] (lazy-query conn sqlvec {}))
  ([conn sqlvec options]
   (let [^java.sql.PreparedStatement stmt (proto/prepared-statement sqlvec conn options)]
     (types/->cursor conn stmt))))

(defn cursor->lazyseq
  "Execute a cursor query and return a lazyseq with results."
  ([cursor] (cursor->lazyseq cursor {}))
  ([cursor options]
   (proto/get-lazyseq cursor options)))

(defmacro with-connection
  "Given database connection paramers (dbspec), creates
  a context with new connection to database that are closed
  at end of code block.

  If dbspec has datasource (connection pool), instead of create
  a new connection, get it from connection pool and release it
  at the end.

  Example:

    (with-connection [conn dbspec]
      (do-somethin-with-connection conn))

  Deprecated but yet working example (this behavior should be
  removed on 1.1 version):

    (with-connection dbspec conn
      (do-something-with conn))
  "
  [dbspec & body]
  (if (vector? dbspec)
    `(with-open [con# (make-connection ~(second dbspec))]
       (let [~(first dbspec) con#]
         ~@body))
    `(with-open [~(first body) (make-connection ~dbspec)]
       ~@(rest body))))
