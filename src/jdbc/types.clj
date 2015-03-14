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

(ns jdbc.types
  (:require [jdbc.proto :as proto]
            [jdbc.util.resultset :refer [result-set->lazyseq]])
  (:import java.sql.Connection
           java.sql.ResultSet
           java.sql.PreparedStatement))

(defn ->connection
  "Create a connection wrapper.

  The connection  wrapper is need because it
  implemens IMeta interface that is mandatory
  for transaction management."
  [^Connection conn]
  (reify
    proto/IConnection
    (connection [_] conn)

    java.io.Closeable
    (close [_]
      (.close conn))))

(defn ->cursor
  [^Connection conn ^PreparedStatement stmt]
  (reify
    proto/IConnection
    (connection [_] conn)

    proto/ICursor
    (get-lazyseq [_ opts]
      (let [^ResultSet rs (.executeQuery stmt)]
        (result-set->lazyseq conn rs opts)))

    java.io.Closeable
    (close [_]
      (.close stmt))))
