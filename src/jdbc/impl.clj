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

(ns jdbc.impl
  "Protocol implementations. Mainly private api"
  (:require [jdbc.proto :as proto]
            [jdbc.types :as types]
            [jdbc.constants :as constants])
  (:import jdbc.types.Connection
           java.sql.PreparedStatement))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocols Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-prepared-statement
  "Given connection and query, return a prepared statement."
  ([conn sqlvec] (make-prepared-statement conn sqlvec {}))
  ([conn sqlvec {:keys [result-type result-concurency fetch-size
                        max-rows holdability lazy returning]
                 :or {result-type :forward-only
                      result-concurency :read-only
                      fetch-size 100}
                 :as options}]
   (let [^java.sql.Connection
         rconn  (:connection conn)
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
            (map-indexed #(proto/set-stmt-parameter! %2 conn stmt (inc %1)))
            (dorun)))
     stmt)))

(extend-protocol proto/ISQLType
  Object
  (as-sql-type [this ^Connection conn] this)
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


(extend-protocol proto/ISQLStatement
  clojure.lang.APersistentVector
  (normalize [sql-with-params conn options]
    (make-prepared-statement conn sql-with-params options))

  String
  (normalize [sql conn options]
    (make-prepared-statement conn [sql] options))

  PreparedStatement
  (normalize [prepared-stmt conn options]
    prepared-stmt))
