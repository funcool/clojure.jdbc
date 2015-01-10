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
  (:require [jdbc.proto :as proto]
            [jdbc.util.resultset :refer [result-set->lazyseq]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Types (Wrappers)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ->connection
  [^java.sql.Connection connection]
  (reify
    proto/IConnection
    (get-connection [_] connection)

    proto/IDatabaseMetadata
    (get-database-metadata [_]
      (.getMetaData connection))

    java.io.Closeable
    (close [_]
      (.close connection))))

(extend-protocol proto/IConnection
  java.sql.Connection
  (get-connection [this] this))

(defn ->cursor
  [conn ^java.sql.PreparedStatement stmt]
  (reify
    proto/IConnection
    (get-connection [_] (proto/get-connection conn))

    proto/ICursor
    (get-lazyseq [_ opts]
      (let [^java.sql.ResultSet rs (.executeQuery stmt)]
        (result-set->lazyseq conn rs opts)))

    java.io.Closeable
    (close [_]
      (.close stmt))))

(defn is-connection?
  "Test if a value is a connection instance."
  [c]
  (satisfies? proto/IConnection c))
