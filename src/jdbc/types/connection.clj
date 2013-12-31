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

(ns jdbc.types.connection)

(def ^:private isolation-level-map
  {:none nil
   :read-commited (java.sql.Connection/TRANSACTION_READ_UNCOMMITTED)
   :repeatable-read (java.sql.Connection/TRANSACTION_REPEATABLE_READ)
   :serializable (java.sql.Connection/TRANSACTION_SERIALIZABLE)})

(defrecord Connection [connection metadata]
  java.lang.AutoCloseable
  (close [this]
    (.close (:connection this))))

(defn is-connection?
  "Test if a value is a connection instance."
  [^Connection c]
  (instance? Connection c))

(defn vendor-name
  "Get connection vendor name."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.getDatabaseProductName (:metadata c)))

(defn catalog-name
  "Given a connection, get a catalog name."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.getCatalog (:connection c)))

(defn schema-name
  "Given a connection, get a schema name."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.getSchema (:connection c)))

(defn is-readonly?
  "Returns true if a current connection is
  in read-only model."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.isReadOnly (:connection c)))

(defn is-valid?
  "Given a connection, return true if connection
  has not ben closed it still valid."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.isValid (:connection c)))

(defn network-timeout
  "Given a connection, get network timeout."
  [^Connection c]
  {:pre [(is-connection? c)]}
  ( .getNetworkTimeout (:connection c)))

(defn isolation-level
  "Given a connection, get a current isolation level."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (let [ilvalue (.getTransactionIsolation (:connection c))]
    (cond
      (= ilvalue java.sql.Connection/TRANSACTION_READ_UNCOMMITTED) :read-commited
      (= ilvalue java.sql.Connection/TRANSACTION_REPEATABLE_READ) :repeatable-read
      (= ilvalue java.sql.Connection/TRANSACTION_SERIALIZABLE) :serializable
      :else :none)))

(defn db-major-version
  "Given a connection, return a database major
  version number."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.getDatabaseMajorVersion (:metadata c)))

(defn db-minor-version
  "Given a connection, return a database minor
  version number."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.getDatabaseMinorVersion (:metadata c)))

(defn db-product-name
  "Given a connection, return a database product
  name from it metadata."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.getDatabaseProductName (:metadata c)))

(defn db-product-version
  "Given a connection, return a database product
  version from it metadata."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.getDatabaseProductVersion (:metadata c)))

(defn driver-name
  "Given a connection, return a current driver name
  used for this connection."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.getDriverName (:metadata c)))

(defn driver-version
  "Given a connection, return a current driver version
  used for this connection."
  [^Connection c]
  {:pre [(is-connection? c)]}
  (.getDriverVersion (:metadata c)))
