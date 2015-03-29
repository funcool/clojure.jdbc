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

(ns jdbc.core-deprecated
  "WARNING: This namespace is deprecated and will be removed in
  clojure.jdbc 0.6.0."
  (:require [clojure.string :as str]
            [jdbc.types :as types]
            [jdbc.impl :as impl]
            [jdbc.proto :as proto]
            [jdbc.resultset :refer [result-set->lazyseq result-set->vector]]
            [jdbc.transaction :as tx]
            [jdbc.constants :as constants])
  (:import java.sql.PreparedStatement
           java.sql.DatabaseMetaData
           java.sql.ResultSet
           java.sql.Connection))

(defn ^{:deprecated true}
  execute-statement!
  "Given a connection statement and paramgroups (can be empty)
  execute the prepared statement and return results from it.

  This is a low level interface and should be used with precaution. This
  function is used internally for execue raw sql such as CREATE/DROP
  table.

  Status: Alpha - Implementation and name of this method can change on
  next versions."
  [conn ^PreparedStatement stmt param-groups]
  (let [^Connection conn (proto/connection conn)]
    (if-not (seq param-groups)
      (seq [(.executeUpdate stmt)])
      (let [set-parameter (fn [^long index value]
                            (proto/set-stmt-parameter! value conn stmt (inc index)))]
        (doseq [pgroup param-groups]
          (dorun (map-indexed set-parameter pgroup))
          (.addBatch stmt))
        (seq (.executeBatch stmt))))))

(defn ^{:deprecated true} execute!
  "Run arbitrary number of raw sql commands such as: CREATE TABLE,
  DROP TABLE, etc... If your want transactions, you can wrap this
  call in transaction using `with-transaction` context block macro
  that is available in  `jdbc.transaction` namespace.

  Warning: not all database servers support ddl in transactions.

  Examples:

      ;; Without transactions
      (with-open [conn (connection dbspec)]
        (execute! conn \"CREATE TABLE foo (id serial, name text);\"))
  "
  [conn & commands]
  (let [^Connection connection (proto/connection conn)]
    (with-open [^PreparedStatement stmt (.createStatement connection)]
      (dorun (map (fn [command]
                    (.addBatch stmt command)) commands))
      (seq (.executeBatch stmt)))))

(defn ^{:deprecated true} get-returning-records
  "Given a executed prepared statement with expected returning
  values. Return a vector of records of returning values.
  Usually is a id of just inserted objects, but in other cases
  can be complete objects."
  [conn ^PreparedStatement stmt]
  (let [^ResultSet rs (.getGeneratedKeys stmt)
        ^Connection conn (proto/connection conn)]
    (result-set->vector conn rs {})))

(defn ^{:deprecated true} is-prepared-statement?
  "Check if specified object is prepared statement."
  [obj]
  (instance? PreparedStatement obj))

(defn ^{:deprecated true} execute-prepared!
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
  (let [^Connection conn (proto/connection conn)
        opts-candidate (last param-groups)
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
     (with-open [^PreparedStatement stmt (proto/prepared-statement sql conn options)]
       (let [res (execute-statement! conn stmt param-groups)]
         (if (:returning options)
           ;; In case of returning key is found on options
           ;; and it has logical true value, build special prepared
           ;; statement that expect return values.
           (get-returning-records conn stmt)
           res))))))

(defn ^{:deprecated true} query
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
   (let [^Connection conn (proto/connection conn)
         ^PreparedStatement stmt (proto/prepared-statement sqlvec conn options)]
     (let [^ResultSet rs (.executeQuery stmt)]
       (result-set->vector conn rs options)))))

(def ^{:deprecated true} query-first
  "Perform a simple sql query and return the first result. It accepts the
  same arguments as the `query` function."
  (comp first query))

(defmacro ^{:deprecated true}
  with-connection
  "Given database connection paramers (dbspec), creates
  a context with new connection to database that are closed
  at end of code block.

  If dbspec has datasource (connection pool), instead of create
  a new connection, get it from connection pool and release it
  at the end.

  WARNING: deprecated

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
    `(with-open [con# (connection ~(second dbspec))]
       (let [~(first dbspec) con#]
         ~@body))
    `(with-open [~(first body) (connection ~dbspec)]
       ~@(rest body))))
