;; Copyright 2014-2015 Andrey Antukh <niwi@niwi.nz>
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

(ns jdbc.impl
  "Protocol implementations. Mainly private api"
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [jdbc.proto :as proto]
            [jdbc.types :as types]
            [jdbc.resultset :refer [result-set->lazyseq result-set->vector]]
            [jdbc.constants :as constants])
  (:import java.net.URI
           java.util.Properties
           java.sql.Connection
           java.sql.DriverManager
           java.sql.PreparedStatement))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Connection constructors implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private dbspec->connection)
(declare ^:private uri->dbspec)
(declare ^:private querystring->map)
(declare ^:private map->properties)

(extend-protocol proto/IConnection
  java.sql.Connection
  (connection [this] this)

  javax.sql.DataSource
  (connection [ds]
    (.getConnection ds))

  clojure.lang.IPersistentMap
  (connection [dbspec]
    (dbspec->connection dbspec))

  java.net.URI
  (connection [uri]
    (-> (uri->dbspec uri)
        (dbspec->connection)))

  java.lang.String
  (connection [uri]
    (proto/connection (java.net.URI. uri))))

(defn- dbspec->connection
  "Create a connection instance from dbspec."
  [{:keys [subprotocol subname user password
           name vendor host port datasource classname]
    :as dbspec}]
  (cond
    (and name vendor)
    (let [host   (or host "127.0.0.1")
          port   (if port (str ":" port) "")
          dbspec (-> (dissoc dbspec :name :vendor :host :port)
                     (assoc :subprotocol vendor
                            :subname (str "//" host port "/" name)))]
      (dbspec->connection dbspec))

    (and subprotocol subname)
    (let [url (format "jdbc:%s:%s" subprotocol subname)
          options (dissoc dbspec :subprotocol :subname)]

      (when classname
        (Class/forName classname))

      (DriverManager/getConnection url (map->properties options)))

    ;; NOTE: only for backward compatibility
    (and datasource)
    (proto/connection datasource)

    :else
    (throw (IllegalArgumentException. "Invalid dbspec format"))))

(defn uri->dbspec
  "Parses a dbspec as uri into a plain dbspec. This function
  accepts `java.net.URI` or `String` as parameter."
  [^URI uri]
  (let [host (.getHost uri)
        port (.getPort uri)
        path (.getPath uri)
        scheme (.getScheme uri)
        userinfo (.getUserInfo uri)]
    (merge
      {:subname (if (pos? port)
                 (str "//" host ":" port path)
                 (str "//" host path))
       :subprotocol scheme}
      (when userinfo
        (let [[user password] (str/split userinfo #":")]
          {:user user :password password}))
      (querystring->map uri))))

(defn- querystring->map
  "Given a URI instance, return its querystring as
  plain map with parsed keys and values."
  [^URI uri]
  (when-let [^String query (.getQuery uri)]
    (when-not (str/blank? query)
     (->> (for [^String kvs (.split query "&")] (into [] (.split kvs "=")))
          (into {})
          (walk/keywordize-keys)))))

(defn- map->properties
  "Convert hash-map to java.utils.Properties instance. This method is used
  internally for convert dbspec map to properties instance, but it can
  be usefull for other purposes."
  [data]
  (let [p (Properties.)]
    (dorun (map (fn [[k v]] (.setProperty p (name k) (str v))) (seq data)))
    p))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IExecute implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend-protocol proto/IExecute
  java.lang.String
  (execute [sql conn opts]
    (with-open [^PreparedStatement stmt (.createStatement ^Connection conn)]
      (.addBatch stmt ^String sql)
      (seq (.executeBatch stmt))))

  clojure.lang.IPersistentVector
  (execute [sqlvec conn opts]
    (with-open [^PreparedStatement stmt (proto/prepared-statement sqlvec conn opts)]
      (let [counts (.executeUpdate stmt)]
        (if (:returning opts)
          (with-open [rs (.getGeneratedKeys stmt)]
            (result-set->vector conn rs opts))
          counts))))

  PreparedStatement
  (execute [^PreparedStatement stmt ^Connection conn opts]
    (.executeUpdate stmt)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IFetch implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend-protocol proto/IFetch
  java.lang.String
  (fetch [^String sql ^Connection conn opts]
    (with-open [^PreparedStatement stmt (proto/prepared-statement sql conn opts)]
      (let [^ResultSet rs (.executeQuery stmt)]
        (result-set->vector conn rs opts))))

  clojure.lang.IPersistentVector
  (fetch [^clojure.lang.IPersistentVector sqlvec ^Connection conn opts]
    (with-open [^PreparedStatement stmt (proto/prepared-statement sqlvec conn opts)]
      (let [^ResultSet rs (.executeQuery stmt)]
        (result-set->vector conn rs opts))))

  PreparedStatement
  (fetch [^PreparedStatement stmt ^Connection conn opts]
    (let [^ResultSet rs (.executeQuery stmt)]
      (result-set->vector conn rs opts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PreparedStatement constructors implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private prepared-statement*)

(extend-protocol proto/IPreparedStatement
  String
  (prepared-statement [sql conn options]
    (prepared-statement* conn [sql] options))

  clojure.lang.IPersistentVector
  (prepared-statement [sql-with-params conn options]
    (prepared-statement* conn sql-with-params options))

  PreparedStatement
  (prepared-statement [o _ _] o))

(defn- prepared-statement*
  "Given connection and query, return a prepared statement."
  ([^Connection conn sqlvec] (prepared-statement* conn sqlvec {}))
  ([^Connection conn sqlvec {:keys [result-type result-concurency fetch-size
                                    max-rows holdability returning]
                             :or {result-type :forward-only
                                  result-concurency :read-only}
                             :as options}]
   (let [sqlvec (if (string? sqlvec) [sqlvec] sqlvec)
         ^String sql (first sqlvec)
         params (rest sqlvec)

         ^PreparedStatement
         stmt (cond
               returning
               (if (or (= :all returning) (true? returning))
                 (.prepareStatement conn sql java.sql.Statement/RETURN_GENERATED_KEYS)
                 (.prepareStatement conn sql
                                    #^"[Ljava.lang.String;" (into-array String (mapv name returning))))

               holdability
               (.prepareStatement conn sql
                                  (result-type constants/resultset-options)
                                  (result-concurency constants/resultset-options)
                                  (holdability constants/resultset-options))
               :else
               (.prepareStatement conn sql
                                  (result-type constants/resultset-options)
                                  (result-concurency constants/resultset-options)))]

     ;; Set fetch-size and max-rows if provided by user
     (when fetch-size (.setFetchSize stmt fetch-size))
     (when max-rows (.setMaxRows stmt max-rows))
     (when (seq params)
       (->> params
            (map-indexed #(proto/set-stmt-parameter! %2 conn stmt (inc %1)))
            (dorun)))
     stmt)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default implementation for type conversions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend-protocol proto/ISQLType
  Object
  (as-sql-type [this conn] this)
  (set-stmt-parameter! [this conn ^PreparedStatement stmt ^Long index]
    (.setObject stmt index (proto/as-sql-type this conn)))

  nil
  (as-sql-type [this conn] nil)
  (set-stmt-parameter! [this conn ^PreparedStatement stmt index]
    (.setObject stmt index (proto/as-sql-type nil conn))))


(extend-protocol proto/ISQLResultSetReadColumn
  Object
  (from-sql-type [this conn metadata i] this)

  Boolean
  (from-sql-type [this conn metadata i] (= true this))

  nil
  (from-sql-type [this conn metadata i] nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- begin*
  [conn opts]
  (let [^Connection rconn (proto/connection conn)
        metadata (-> (meta conn)
                     (assoc :rollback (atom false)
                            :prev-isolation (.getTransactionIsolation rconn)
                            :prev-readonly (.isReadOnly rconn)))]
    (if (:transaction metadata)
      (let [sp (.setSavepoint rconn)]
        (with-meta conn
          (assoc metadata :savepoint sp :transaction true)))

      (let [prev-autocommit (.getAutoCommit rconn)]
        (.setAutoCommit rconn false)
        (when-let [isolation (:isolation-level opts)]
          (.setTransactionIsolation rconn (get constants/isolation-levels isolation)))
        (when-let [read-only (:read-only opts)]
          (.setReadOnly rconn read-only))
        (with-meta conn
          (assoc metadata :prev-autocommit prev-autocommit :transaction true))))))

(defn- commit*
  [ts conn opts]
  (let [^Connection rconn (proto/connection conn)
        metadata  (meta conn)]
    ;; In case on commit and rollback flag is set, commit action
    ;; should be ignored and rollback will performed.
    (if @(:rollback metadata)
      (proto/rollback! ts conn opts)
      (if-let [savepoint (:savepoint metadata)]
        (.releaseSavepoint rconn savepoint)
        (do
          (.commit rconn)
          (.setAutoCommit rconn (:prev-autocommit metadata))
          (.setTransactionIsolation rconn (:prev-isolation metadata))
          (.setReadOnly rconn (:prev-readonly metadata)))))))

(defn- rollback*
  [conn opts]
  (let [^Connection rconn (proto/connection conn)
        metadata (meta conn)]
    (if-let [savepoint (:savepoint metadata)]
      (.rollback rconn savepoint)
      (do
        (.rollback rconn)
        (.setAutoCommit rconn (:prev-autocommit metadata))
        (.setTransactionIsolation rconn (:prev-isolation metadata))
        (.setReadOnly rconn (:prev-readonly metadata))))))

(defn transaction-strategy
  []
  (reify proto/ITransactionStrategy
    (begin! [_ conn opts] (begin* conn opts))
    (rollback! [_ conn opts] (rollback* conn opts))
    (commit! [ts conn opts] (commit* ts conn opts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lazy Query
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cursor->lazyseq
  [cursor opts]
  (let [^PreparedStatement stmt (.-stmt cursor)
        ^Connection conn (.getConnection stmt)
        ^ResultSet rs (.executeQuery stmt)]
    (result-set->lazyseq conn rs opts)))
