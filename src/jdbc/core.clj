;; Copyright 2013 Andrey Antukh <niwi@niwi.be>
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
            [jdbc.types :as types :refer [->Connection ->ResultSet is-connection?]]
            [jdbc.util :refer [with-exception raise-exc]]
            [jdbc.transaction :as tx]
            [jdbc.constants :as constants])
  (:import java.net.URI
           java.sql.DriverManager
           java.sql.PreparedStatement
           java.sql.ResultSet
           java.sql.Statement
           java.util.Properties)
  (:refer-clojure :exclude [resultset-seq]))

(defn ^Properties map->properties
  "Convert hash-map to java.utils.Properties instance. This method is used
  internally for convert dbspec map to properties instance, but it can
  be usefull for other purposes."
  [data]
  (let [p (Properties.)]
    (dorun (map (fn [[k v]] (.setProperty p (name k) (str v))) (seq data)))
    p))

(defn- strip-jdbc-prefix
  "Simple util function that strip a \"jdbc:\" prefix
  from connection string urls."
  [^String url]
  (str/replace-first url #"^jdbc:" ""))

(defn uri->dbspec
  "Parses a dbspec as uri into a plain dbspec. This function
  accepts `java.net.URI` or `String` as parameter."
  [url]
  (let [^URI uri      (if (instance? URI url) url
                          (URI. (strip-jdbc-prefix url)))
             host     (.getHost uri)
             port     (.getPort uri)
             path     (.getPath uri)
             scheme   (.getScheme uri)
             userinfo (.getUserInfo uri)]
    (merge
      {:subname (if (pos? port)
                 (str "//" host ":" port path)
                 (str "//" host path))
       :subprotocol scheme}
      (when userinfo
        (let [[user password] (str/split userinfo #":")]
          {:user user :password password})))))

(defn result-set->lazyseq
  "Function that wraps result in a lazy seq. This function
  is part of public api but can not be used directly (you should pass
  this function as parameter to `query` function).

  Required parameters:
    rs: ResultSet instance.

  Optional named parameters:
    :identifiers -> function that is applied for column name
                    when as-arrays? is false
    :as-rows?    -> by default this function return a lazy seq of
                    records (map), but in certain circumstances you
                    need results as a lazy-seq of vectors. With this keywork
                    parameter you can enable this behavior and return a lazy-seq
                    of vectors instead of records (maps).
  "
  [conn, ^ResultSet rs & [{:keys [identifiers as-rows?]
                                       :or {identifiers str/lower-case as-rows? false}
                                       :as options}]]
  (let [metadata    (.getMetaData rs)
        idseq       (range 1 (inc (.getColumnCount metadata)))
        keyseq      (->> idseq
                         (map (fn [^long i] (.getColumnLabel metadata i)))
                         (map (comp keyword identifiers)))
        values      (fn [] (map (fn [^long i] (types/from-sql-type (.getObject rs i) conn metadata i)) idseq))
        records     (fn thisfn []
                      (when (.next rs)
                        (cons (zipmap keyseq (values)) (lazy-seq (thisfn)))))
        rows        (fn thisfn []
                      (when (.next rs)
                        (cons (vec (values)) (lazy-seq (thisfn)))))]
    (if as-rows? (rows) (records))))

(defn result-set->vector
  "Function that evaluates a result into one clojure persistent
  vector. Accept same parameters as `result-set->lazyseq`."
  [& args]
  (vec (apply result-set->lazyseq args)))

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
    (let [set-parameter (fn [index value]
                          (types/set-stmt-parameter! value conn stmt (inc index)))]
      (doseq [pgroup param-groups]
        (dorun (map-indexed set-parameter pgroup))
        (.addBatch stmt))
      (seq (.executeBatch stmt)))))

(defn- make-raw-connection-from-jdbcurl
  "Given a url and optionally params, returns a raw jdbc connection."
  ([url opts] (DriverManager/getConnection url (map->properties opts)))
  ([url] (DriverManager/getConnection url)))

(defn- make-raw-connection-from-dbspec
  "Given a plain dbspec, converts it to a valid jdbc url with
  optionally options and pass it to ``make-raw-connection-from-jdbcurl``"
  [{:keys [subprotocol subname] :as dbspec}]
  (let [url     (format "jdbc:%s:%s" subprotocol subname)
        options (dissoc dbspec :subprotocol :subname)]
    (make-raw-connection-from-jdbcurl url options)))

(defn- make-raw-connection
  "Given connection parametes get raw jdbc connection. This
  function is private and is used directly by `make-connection`."
  [{:keys [connection-uri subprotocol subname
           user password read-only schema
           isolation-level name vendor host port
           ^javax.sql.DataSource datasource]
    :or {read-only false schema nil}
    :as dbspec}]
  (cond
   (and datasource user password)
   (.getConnection datasource user password)

   (and datasource)
   (.getConnection datasource)

   (and subprotocol subname)
   (make-raw-connection-from-dbspec dbspec)

   (and name vendor)
   (let [host   (or host "127.0.0.1")
         port   (if port (str ":" port) "")
         dbspec (merge
                 {:subprotocol vendor
                  :subname (str "//" host port "/" name)}
                 (when (and user password)
                   {:user user
                    :password password}))]
     (make-raw-connection-from-dbspec dbspec))

   (and connection-uri)
   (make-raw-connection-from-jdbcurl connection-uri)

   (or (string? dbspec) (instance? URI dbspec))
   (make-raw-connection-from-dbspec (uri->dbspec dbspec))
   :else (throw (IllegalArgumentException. "Invalid dbspec format"))))

(defn make-connection
  "Creates a connection to a database from dbspec, and dbspec
  can be:

  - map containing connection parameter
  - map containing a datasource
  - URI or string

  The dbspec map has this possible variants:

  Classic approach:
    :subprotocol -> (required) string that represents a vendor name (ex: postgresql)
    :subname -> (required) string that represents a database name (ex: test)
    :classname -> (optional) string that represents a class name.
    (many others options that are pased directly as driver parameters)

  Pretty format:
    :vendor -> (required) string that represents a vendor name (ex: postgresql)
    :name -> (required) string that represents a database name (ex: test)
    :host -> (optional) string that represents a database hostname (default: 127.0.0.1)
    :port -> (optional) long number that represents a database port (default: driver default)
    (many others options that are pased directly as driver parameters)

  Raw format:
    :connection-uri -> String that passed directly to DriverManager/getConnection

  URI or String format:
    vendor://user:password@host:post/dbname

  Additional options for map based dbspecs:
    :schema -> string that represents a schema name (default: nil)
    :read-only -> boolean for mark entire connection read only.

  For more details, see documentation."
  [{:keys [isolation-level schema read-only]
    :or {read-only false schema nil}
    :as dbspec}]
  (let [^java.sql.Connection       rawconn  (make-raw-connection dbspec)
        ^java.sql.DatabaseMetaData metadata (.getMetaData rawconn)]
    (when isolation-level
      (.setTransactionIsolation rawconn (get constants/isolation-levels isolation-level)))
    (when schema
      (.setSchema rawconn schema))
    (.setReadOnly rawconn read-only)
    (-> (->Connection rawconn metadata)
        (assoc :isolation-level isolation-level))))

(defn execute!
  "Run arbitrary number of raw sql commands such as: CREATE TABLE,
  DROP TABLE, etc... If your want transactions, you can wrap this
  call in transaction using `with-transaction` context block macro
  that is available in  ``jdbc.transaction`` namespace.

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
  {:pre [(is-connection? conn)]}
  (let [^java.sql.Connection connection (:connection conn)]
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
  {:pre [(is-connection? conn)]}
  (let [rs (.getGeneratedKeys stmt)]
    (result-set->vector conn rs)))

(defn is-prepared-statement?
  "Check if specified object is prepared statement."
  [obj]
  (instance? PreparedStatement obj))

(defn make-prepared-statement
  "Given connection and query, return a prepared statement."
  ([conn sqlvec] (make-prepared-statement conn sqlvec {}))
  ([conn sqlvec {:keys [result-type result-concurency fetch-size
                        max-rows holdability lazy returning]
                 :or {result-type :forward-only
                      result-concurency :read-only
                      fetch-size 100}
                 :as options}]
   {:pre [(is-connection? conn) (or (string? sqlvec) (vector? sqlvec))]}
   (let [^java.sql.Connection
         rconn  (:connection conn)
         sqlvec (if (string? sqlvec) [sqlvec] sqlvec)
         sql    (first sqlvec)
         params (rest sqlvec)

         ^java.sql.PreparedStatement
         stmt   (cond
                 returning
                 (if (= :all returning)
                   (.prepareStatement rconn sql java.sql.Statement/RETURN_GENERATED_KEYS)
                   (.prepareStatement rconn sql (into-array String (mapv name returning))))

                 holdability
                 (.prepareStatement rconn sql
                                    (result-type constants/resultset-options)
                                    (result-concurency constants/resultset-options)
                                    (holdability constants/resultset-options))
                 :else
                 (.prepareStatement rconn sql
                                    (result-type constants/resultset-options)
                                    (result-concurency constants/resultset-options)))]

     ;; Lazy resultset works with database cursors ant them can not be used
     ;; without one transaction
     (when (and (not (:in-transaction conn)) lazy)
       (throw (IllegalArgumentException. "Can not use cursor resultset without transaction")))

     ;; Overwrite default jdbc driver fetch-size when user
     ;; wants lazy result set.
     (when lazy (.setFetchSize stmt fetch-size))

     ;; Set fetch-size and max-rows if provided by user
     (when fetch-size (.setFetchSize stmt fetch-size))
     (when max-rows (.setMaxRows stmt max-rows))
     (when (seq params)
       (->> params
            (map-indexed #(types/set-stmt-parameter! %2 conn stmt (inc %1)))
            (dorun)))
     stmt)))

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
  {:pre [(is-connection? conn)]}
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
     (with-open [^java.sql.PreparedStatement stmt (types/normalize sql conn options)]
       (let [res (execute-statement! conn stmt param-groups)]
         (if (:returning options)
           ;; In case of returning key is found on options
           ;; and it has logical true value, build special prepared
           ;; statement that expect return values.
           (get-returning-records conn stmt)
           res))))))

(extend-protocol types/ISQLStatement
  clojure.lang.APersistentVector
  (normalize [sql-with-params conn options]
    (make-prepared-statement conn sql-with-params options))

  String
  (normalize [sql conn options]
    (make-prepared-statement conn [sql] options))

  PreparedStatement
  (normalize [prepared-stmt conn options]
    prepared-stmt))

(defn make-query
  "Given a connection and paramatrized sql, execute a query and
  return a instance of ResultSet that works as stantard clojure
  map but implements a closable interface.

  A returned ``jdbc.types.resultset.ResultSet`` works as a wrapper
  around a prepared statement and java.sql.ResultSet mostly used for
  server side cursors properly resource management.

  This functions indents be a low level access for making queries
  and it delegate to a user the resource management.

  NOTE: It strongly recommended not use this function directly and use a ``with-query``
  macro for make query thar returns large amount of data or simple ``query`` function
  that returns directly a evaluated result.

  Example using parametrized sql:

    (with-open [result (make-query conn [\"SELECT foo FROM bar WHERE id = ?\" 1])]
      (doseq [row (:data result)]
        (println row)))

  Example using plain sql (without parameters):

    (with-open [result (make-query conn \"SELECT version();\")]
      (doseq [row (:data result)]
        (println row)))

  Example using extern prepared statement:

    (let [stmt (make-prepared-statement conn [\"SELECT foo FROM bar WHERE id = ?\" 1])]
      (with-open [result (make-query conn stmt)]
        (doseq [row (:data result)]
          (println row))))"
  ([conn sql-with-params] (make-query conn sql-with-params {}))
  ([conn sql-with-params {:keys [fetch-size lazy] :or {lazy false} :as options}]
   (let [^java.sql.PreparedStatement stmt (types/normalize sql-with-params conn options)]
     (let [^ResultSet rs (.executeQuery stmt)]
       (if (not lazy)
         (->ResultSet stmt rs false (result-set->vector conn rs options))
         (->ResultSet stmt rs true (result-set->lazyseq conn rs options)))))))

(defn query
  "Perform a simple sql query and return a evaluated result as vector.

  ``sqlvec`` parameter can be: parametrized sql (vector format), plain sql
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
  ([conn sqlvec {:keys [lazy] :or {lazy false} :as options}]
   (let [^java.sql.PreparedStatement stmt (types/normalize sqlvec conn options)]
     (let [^ResultSet rs (.executeQuery stmt)]
       (result-set->vector conn rs options)))))

(def query-first
  "Perform a simple sql query and return the first result. It accepts the
  same arguments as the ``query`` function."
  (comp first query))

(defmacro with-query
  "Idiomatic dsl macro for ``query`` function that handles well queries
  what returns a huge amount of results.

  ``sqlvec`` can be in same formats as in ``query`` function.

  NOTE: This method ensueres a query in one implicit transaction.

  Example:

    (with-query conn results
      [\"SELECT name FROM people WHERE id = ?\" 1]
      (doseq [row results]
        (println row)))
  "
  [conn bindname sqlvec & body]
  `(tx/with-transaction ~conn
     (with-open [rs# (make-query ~conn ~sqlvec {:lazy true})]
       (let [~bindname (:data rs#)]
         ~@body))))

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
