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

(ns jdbc
  "Alternative implementation of jdbc wrapper for clojure."
  (:import (java.net URI)
           (java.sql BatchUpdateException DriverManager
                     PreparedStatement ResultSet SQLException Statement Types)
           (java.util Hashtable Map Properties)
           (javax.sql DataSource))
  (:require [clojure.string :as str]
            [jdbc.types.connection :refer [->Connection is-connection?]]
            [jdbc.types.resultset :refer [->ResultSet]]
            [jdbc.types :as types]
            [jdbc.transaction :as tx])
  (:refer-clojure :exclude [resultset-seq])
  (:gen-class))

(def ^:private isolation-level-map
  {:none nil
   :read-commited (java.sql.Connection/TRANSACTION_READ_UNCOMMITTED)
   :repeatable-read (java.sql.Connection/TRANSACTION_REPEATABLE_READ)
   :serializable (java.sql.Connection/TRANSACTION_SERIALIZABLE)})

(defn map->properties
  "Convert hash-map to java.utils.Properties instance. This method is used
  internally for convert dbspec map to properties instance, but it can
  be usefull for other purposes.
  "
  [data]
  (let [p (Properties.)]
    (dorun (map (fn [[k v]] (.setProperty p (name k) (str v))) (seq data)))
    p))

(defn- strip-jdbc-prefix
  "Siple util function that strip a \"jdbc:\" prefix
  from connection string urls."
  [^String url]
  (str/replace-first url #"^jdbc:" ""))

(defn uri->dbspec
  "Parses a dbspec as uri into a plain dbspec. This function
  accepts ``java.net.URI`` or ``String`` as parameter."
  [url]
  (let [uri       (if (instance? URI url) url (URI. (strip-jdbc-prefix url)))
        host      (.getHost uri)
        port      (.getPort uri)
        path      (.getPath uri)
        scheme    (.getScheme uri)
        userinfo  (.getUserInfo uri)]
    (merge
      {:subname (if (pos? port)
                 (str "//" host ":" port path)
                 (str "//" host path))
      :subprotocol scheme}
      (when userinfo
        (let [[user password] (str/split userinfo #":")]
          {:user user :password password})))))

(defn- wrap-isolation-level
  "Wraps and handles a isolation level for connection."
  [dbspec conn]
  (let [raw-connection  (:connection conn)
        isolation-level (:isolation-level dbspec)]
    (if-let [isolation-level-value (get isolation-level-map isolation-level)]
      (do
        (.setTransactionIsolation raw-connection isolation-level-value)
        (assoc conn :isolation-level isolation-level))
      (assoc conn :isolation-level :none))))

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
  [conn rs & [{:keys [identifiers as-rows?]
         :or {identifiers str/lower-case as-rows? false}
         :as options}]]
  (let [metadata    (.getMetaData rs)
        idseq       (range 1 (inc (.getColumnCount metadata)))
        keyseq      (->> idseq
                         (map (fn [i] (.getColumnLabel metadata i)))
                         (map (comp keyword identifiers)))
        values      (fn [] (map (fn [i] (types/from-sql-type (.getObject rs i) conn metadata i)) idseq))
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
  "Given a plain statement instance, execute it throught its asociated
  connection and return a raw seq of results.

  This is a low level interface and should be used with precaution. This
  function is used internally for execue raw sql such as CREATE/DROP
  table.

  Status: Alpha - Implementation and name of this method can change on
  following versions.
  "
  [stmt]
  {:pre [(instance? Statement stmt)]}
  (seq (.executeBatch stmt)))

(defn execute-statement->query-result
  "Given a plain or prepared statement instance, return
  a ResultSet instance.

  This is a low level interface and should be used with precaution.

  WARNING: untested

  Status: Alpha - Implementation and name of this method can change on
  following versions.
  "
  ([conn statement] (execute-statement->query-result conn statement {}))
  ([conn statement options]
   (let [fetch-size  (.getFetchSize statement)
         rs          (.executeQuery statement)]
     (if (= fetch-size 0)
       (->ResultSet statement rs false (result-set->vector conn rs))
       (->ResultSet statement rs true (result-set->lazyseq conn rs))))))

(def ^:private resultset-constants
   ;; Type
  {:forward-only ResultSet/TYPE_FORWARD_ONLY
   :scroll-insensitive ResultSet/TYPE_SCROLL_INSENSITIVE
   :scroll-sensitive ResultSet/TYPE_SCROLL_SENSITIVE

   ;; Cursors
   :hold ResultSet/HOLD_CURSORS_OVER_COMMIT
   :close ResultSet/CLOSE_CURSORS_AT_COMMIT

   ;; Concurrency
   :read-only ResultSet/CONCUR_READ_ONLY
   :updatable ResultSet/CONCUR_UPDATABLE})

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

(defn make-connection
  "Creates a connection to a database.

  Here some simple examples, but if you want more detailed information,
  please read a documentation:

    ;; Using a plain dbspec
    (with-open [c (make-connection {:subprotocol \"h2\" :subname \"mem:\"})]
      (do-somethin-with-connection c))

    ;; Using raw jdbc connection url
    (with-open [c (make-connection \"postgresql://user:pass@localhost/test\")]
      (do-somethin-with-connection c))"
  [{:keys [connection-uri subprotocol subname
           datasource user password read-only schema]
    :or {read-only false schema nil}
    :as dbspec}]
  (let [raw-connection  (cond
                          (and datasource user password)
                            (.getConnection datasource user password)
                          (and datasource)
                            (.getConnection datasource)
                          (and subprotocol subname)
                            (make-raw-connection-from-dbspec dbspec)
                          (and connection-uri)
                            (make-raw-connection-from-jdbcurl connection-uri)
                          (or (string? dbspec) (instance? URI dbspec))
                            (make-raw-connection-from-dbspec (uri->dbspec dbspec))
                          :else
                            (throw (IllegalArgumentException. "Invalid dbspec format")))
        metadata        (.getMetaData raw-connection)
        connection      (->Connection raw-connection metadata)]
    (wrap-isolation-level dbspec connection)))

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
  (let [connection (:connection conn)]
    (with-open [stmt (.createStatement connection)]
      (dorun (map (fn [command]
                    (.addBatch stmt command)) commands))
      (execute-statement! stmt))))

(defn execute-prepared!
  "Same as `execute!` function, but works with PreparedStatement
  instead with plain Statement.

  With this you can execute multiple operations throught
  one call, such as bulk update.

  Example:

    (with-connection dbspec conn
      (let [sql 'UPDATE TABLE foo SET x = ? WHERE y = ?;']
        (execute-prepared! conn sql [1 2] [2 3] [3 4])))

    This code should send this sql sentences:

      UPDATE TABLE foo SET x = 1 WHERE y = 2;
      UPDATE TABLE foo SET x = 2 WHERE y = 3;
      UPDATE TABLE foo SET x = 3 WHERE y = 4;
  "
  [conn sql & param-groups]
  {:pre [(is-connection? conn)]}
  (let [connection (:connection conn)]
    (with-open [stmt (.prepareStatement connection sql)]
      (doseq [param-group param-groups]
        (dorun (map-indexed (fn [index value] (types/set-stmt-parameter! value conn stmt (inc index))) param-group))
        (.addBatch stmt))
      (execute-statement! stmt))))

(defn make-prepared-statement
  "Given connection and parametrized query as vector with first
  argument as string and other arguments as params, return a
  prepared statement.

  Example:

    (let [stmt (make-prepared-statement conn [\"SELECT foo FROM bar WHERE id = ?\" 1])]
      (println (instance? java.sql.PreparedStatement stmt)))
    ;; -> true
  "
  ([conn sqlvec] (make-prepared-statement conn sqlvec {}))
  ([conn sqlvec {:keys [result-type result-concurency fetch-size max-rows holdability lazy]
                 :or {result-type :forward-only result-concurency :read-only fetch-size 100}
                 :as options}]
   {:pre [(is-connection? conn) (vector? sqlvec)]}
   (let [connection (:connection conn)
         sql        (first sqlvec)
         params     (rest sqlvec)
         stmt       (if holdability
                      (.prepareStatement connection sql
                                         (result-type resultset-constants)
                                         (result-concurency resultset-constants)
                                         (holdability resultset-constants))
                      (.prepareStatement connection sql
                                         (result-type resultset-constants)
                                         (result-concurency resultset-constants)))]
     ;; Lazy resultset works with database cursors ant them can not be used
     ;; without one transaction
     (when (and (not (:in-transaction conn)) lazy)
       (throw (RuntimeException. "Can not use cursor resultset without transaction")))

     ;; Overwrite default jdbc driver fetch-size when user
     ;; wants lazy result set.
     (when lazy (.setFetchSize stmt fetch-size))
     (when max-rows (.setMaxRows max-rows))
     (when (seq params)
       (dorun (map-indexed #(.setObject stmt (inc %1) (types/as-sql-type %2 conn)) params)))
     stmt)))

(defn make-query
  "Given a connection and paramatrized sql, execute a query and
  return a instance of ResultSet that works as stantard clojure
  map but implements a closable interface.

  This functions indents be a low level access for making queries
  and it delegate to a user the resource management. You should
  use ``with-open`` macro for store a result as example:

    (with-open [result (make-query conn [\"SELECT foo FROM bar WHERE id = ?\" 1])]
      (doseq [row (:data result)]
        (println row)))

  A ResultSet contains a these keys:

  - ``:stmt`` as PreparedStatement instance
  - ``:rs`` as ResultSet instance
  - ``:data`` as seq of results (can be lazy or not depending on additional parameters)

  NOTE: It strongly recommended not use this function directly and use a ``with-query``
  macro for make query thar returns large amount of data or simple ``query`` function
  that returns directly a evaluated result.
  "
  ([conn sql-with-params] (make-query conn sql-with-params {}))
  ([conn sql-with-params {:keys [fetch-size lazy] :or {lazy false} :as options}]
   {:pre [(or (vector? sql-with-params) (string? sql-with-params))
          (is-connection? conn)]}
   (let [connection (:connection conn)
         stmt       (cond
                      (vector? sql-with-params)
                      (make-prepared-statement conn sql-with-params options)

                      (string? sql-with-params)
                      (make-prepared-statement conn [sql-with-params] options)

                      :else
                      (throw (IllegalArgumentException. "Invalid arguments")))]
     (let [rs (.executeQuery stmt)]
       (if (not lazy)
         (->ResultSet stmt rs false (result-set->vector conn rs options))
         (->ResultSet stmt rs true (result-set->lazyseq conn rs options)))))))

(defn query
  "Perform a simple sql query and return a evaluated result as vector."
  ([conn sqlvec] (query conn sqlvec {}))
  ([conn sqlvec options]
   {:pre [(or (vector? sqlvec) (string? sqlvec))
          (is-connection? conn)]}
   (with-open [result (make-query conn sqlvec (assoc options :lazy false))]
     (:data result))))

(defmacro with-query
  "Idiomatic dsl macro for ``query`` function that handles well queries
  what returns a huge amount of results.

  This method ensueres a query in one implicit transaction or
  subtransaction.

  Example:

    (with-query conn results
      [\"SELECT name FROM people WHERE id = ?\" 1]
      (doseq [row results]
        (println row)))
  "
  [conn bindname sql-with-params & body]
  `(tx/with-transaction ~conn
     (with-open [rs# (make-query ~conn ~sql-with-params {:lazy true})]
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

    (with-connection dbspec conn
      (do-something-with conn))
  "
  [dbspec bindname & body]
  `(with-open [~bindname (make-connection ~dbspec)]
     ~@body))
