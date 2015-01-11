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

(ns jdbc.impl
  "Protocol implementations. Mainly private api"
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [jdbc.proto :as proto]
            [jdbc.types :as types]
            [jdbc.constants :as constants])
  (:import java.net.URI
           java.util.Properties
           java.sql.DriverManager
           java.sql.PreparedStatement))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IConnectionConstructor Protocol Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private dbspec->connection)
(declare ^:private uri->dbspec)

(extend-protocol proto/IConnectionConstructor
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

(declare ^:private querystring->map)
(declare ^:private map->properties)

(defn- dbspec->connection
  "Create a connection instance from dbspec."
  [{:keys [subprotocol subname user password
           name vendor host port datasource]
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
  (let [^String query (.getQuery uri)]
    (->> (for [^String kvs (.split query "&")] (into [] (.split kvs "=")))
         (into {})
         (walk/keywordize-keys))))

(defn- map->properties
  "Convert hash-map to java.utils.Properties instance. This method is used
  internally for convert dbspec map to properties instance, but it can
  be usefull for other purposes."
  [data]
  (let [p (Properties.)]
    (dorun (map (fn [[k v]] (.setProperty p (name k) (str v))) (seq data)))
    p))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IPreparedStatementConstructor Protocol Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private make-prepared-statement)

(extend-protocol proto/IPreparedStatementConstructor
  clojure.lang.APersistentVector
  (prepared-statement [sql-with-params conn options]
    (make-prepared-statement conn sql-with-params options))

  String
  (prepared-statement [sql conn options]
    (make-prepared-statement conn [sql] options))

  PreparedStatement
  (prepared-statement [o _ _] o))

(defn- make-prepared-statement
  "Given connection and query, return a prepared statement."
  ([conn sqlvec] (make-prepared-statement conn sqlvec {}))
  ([conn sqlvec {:keys [result-type result-concurency fetch-size
                        max-rows holdability returning]
                 :or {result-type :forward-only
                      result-concurency :read-only
                      fetch-size 100}
                 :as options}]
   (let [^java.sql.Connection rconn  (proto/get-connection conn)
         sqlvec (if (string? sqlvec) [sqlvec] sqlvec)
         ^String sql (first sqlvec)
         params (rest sqlvec)

         ^java.sql.PreparedStatement
         stmt   (cond
                 returning
                 (if (= :all returning)
                   (.prepareStatement rconn sql java.sql.Statement/RETURN_GENERATED_KEYS)
                   (.prepareStatement rconn sql
                                      #^"[Ljava.lang.String;" (into-array String (mapv name returning))))

                 holdability
                 (.prepareStatement rconn sql
                                    (result-type constants/resultset-options)
                                    (result-concurency constants/resultset-options)
                                    (holdability constants/resultset-options))
                 :else
                 (.prepareStatement rconn sql
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
  (set-stmt-parameter! [this conn ^java.sql.PreparedStatement stmt ^Long index]
    (.setObject stmt index (proto/as-sql-type this conn)))

  nil
  (as-sql-type [this conn] nil)
  (set-stmt-parameter! [this conn ^java.sql.PreparedStatement stmt index]
    (.setObject stmt index (proto/as-sql-type nil conn))))


(extend-protocol proto/ISQLResultSetReadColumn
  Object
  (from-sql-type [this conn metadata i] this)

  Boolean
  (from-sql-type [this conn metadata i] (if (true? this) this false))

  nil
  (from-sql-type [this conn metadata i] nil))


