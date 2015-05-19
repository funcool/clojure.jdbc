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

(ns jdbc.meta
  "Connection metadata access methods."
  (:require [jdbc.types :as types]
            [jdbc.proto :as proto]))

(defn vendor-name
  "Get connection vendor name."
  [c]
  (let [^java.sql.DatabaseMetaData meta (:metadata c)]
    (.getDatabaseProductName meta)))

(defn catalog-name
  "Given a connection, get a catalog name."
  [ c]
  (let [^java.sql.Connection conn (proto/connection c)]
    (.getCatalog conn)))

(defn schema-name
  "Given a connection, get a schema name."
  [c]
  (let [^java.sql.Connection conn (proto/connection c)]
    (.getSchema conn)))

(defn is-readonly?
  "Returns true if a current connection is
  in read-only model."
  [c]
  (let [^java.sql.Connection conn (proto/connection c)]
    (.isReadOnly conn)))

(defn is-valid?
  "Given a connection, return true if connection
  has not ben closed it still valid."
  ([c]
     (is-valid? c 0))
  ([c ^long timeout]
     (let [^java.sql.Connection conn (proto/connection c)]
       (.isValid conn timeout))))

(defn network-timeout
  "Given a connection, get network timeout."
  [c]
  (let [^java.sql.Connection conn (proto/connection c)]
    (.getNetworkTimeout conn)))

(defn isolation-level
  "Given a connection, get a current isolation level."
  [c]
  (let [^java.sql.Connection conn (proto/connection c)
        ilvalue (.getTransactionIsolation conn)]
    (condp = ilvalue
      java.sql.Connection/TRANSACTION_READ_UNCOMMITTED :read-commited
      java.sql.Connection/TRANSACTION_REPEATABLE_READ  :repeatable-read
      java.sql.Connection/TRANSACTION_SERIALIZABLE     :serializable
      :none)))

(defn db-major-version
  "Given a connection, return a database major
  version number."
  [c]
  (let [^java.sql.DatabaseMetaData meta (proto/get-database-metadata c)]
    (.getDatabaseMajorVersion meta)))

(defn db-minor-version
  "Given a connection, return a database minor
  version number."
  [c]
  (let [^java.sql.DatabaseMetaData meta (proto/get-database-metadata c)]
    (.getDatabaseMinorVersion meta)))

(defn db-product-name
  "Given a connection, return a database product
  name from it metadata."
  [c]
  (let [^java.sql.DatabaseMetaData meta (proto/get-database-metadata c)]
    (.getDatabaseProductName meta)))

(defn db-product-version
  "Given a connection, return a database product
  version from it metadata."
  [c]
  (let [^java.sql.DatabaseMetaData meta (proto/get-database-metadata c)]
    (.getDatabaseProductVersion meta)))

(defn driver-name
  "Given a connection, return a current driver name
  used for this connection."
  [c]
  (let [^java.sql.DatabaseMetaData meta (proto/get-database-metadata c)]
    (.getDriverName meta)))

(defn driver-version
  "Given a connection, return a current driver version
  used for this connection."
  [c]
  (let [^java.sql.DatabaseMetaData meta (proto/get-database-metadata c)]
    (.getDriverVersion meta)))
