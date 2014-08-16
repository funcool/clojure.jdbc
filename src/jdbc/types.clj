;; Copyright 2014 Andrey Antukh <niwi@niwi.be>
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

(ns jdbc.types
  "Namespace that encapsulates all related to types and logic
  for extend them.")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Types (Wrappers)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Connection [^java.sql.Connection connection
                       ^java.sql.DatabaseMetaData metadata]
  java.io.Closeable
  (close [this]
    (.close connection)))

(defrecord ResultSet [^java.sql.PreparedStatement stmt
                      ^java.sql.ResultSet rs
                      lazy data]
  java.io.Closeable
  (close [this]
    (.close rs)
    (.close stmt)))

(defn is-connection?
  "Test if a value is a connection instance."
  [^Connection c]
  (instance? Connection c))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocols definition
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

(defprotocol ISQLStatement
  (normalize [this conn options]))

(extend-protocol ISQLType
  Object
  (as-sql-type [this conn] this)
  (set-stmt-parameter! [this conn stmt index]
    (.setObject stmt index (as-sql-type this conn)))

  nil
  (as-sql-type [this conn] nil)
  (set-stmt-parameter! [this conn stmt index]
    (.setObject stmt index (as-sql-type nil conn))))

(extend-protocol ISQLResultSetReadColumn
  Object
  (from-sql-type [this conn metadata i] this)

  Boolean
  (from-sql-type [this conn metadata i] (if (true? this) this false))

  nil
  (from-sql-type [this conn metadata i] nil))
