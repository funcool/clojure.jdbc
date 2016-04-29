;; Copyright 2014-2016 Andrey Antukh <niwi@niwi.nz>
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

(ns jdbc.proto)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Internal Protocols
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IConnection
  "Represents a connection like object that wraps
  a raw jdbc connection with some other data."
  (connection [_] "Create or obtain existing connection"))

(defprotocol IExecute
  (execute [q conn opts] "Execute a query and return a number of rows affected."))

(defprotocol IFetch
  (fetch [q conn opts] "Fetch eagerly results executing query."))

(defprotocol IDatabaseMetadata
  "Allows uniform database metadata extraction."
  (get-database-metadata [_] "Get metadata instance."))

(defprotocol IPreparedStatement
  "Responsible of building prepared statements."
  (prepared-statement [_ connection options] "Create a prepared statement."))

(defprotocol ITransactionStrategy
  (begin! [_ conn opts] "Starts a transaction and return a connection instance.")
  (rollback! [_ conn opts] "Rollbacks a transaction. Returns nil.")
  (commit! [_ conn opts] "Commits a transaction. Returns nil."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SQL Extension Protocols
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ISQLType
  "Protocol that exposes uniform way for convert user
  types to sql/jdbc compatible types and uniform set parameters
  to prepared statement instance. Default implementation available
  for Object and nil values."

  (as-sql-type [_ conn] "Convert user type to sql type.")
  (set-stmt-parameter! [this conn stmt index] "Set value to statement."))

(defprotocol ISQLResultSetReadColumn
  "Protocol that exposes uniform way to convert values
  obtained from result set to user types. Default implementation
  available for Object, Boolean, and nil."

  (from-sql-type [_ conn metadata index] "Convert sql type to user type."))
