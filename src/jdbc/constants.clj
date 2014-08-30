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

(ns jdbc.constants
  "Namespace that contains a public constants
  used around this library."
  (:import java.sql.ResultSet
           java.sql.Connection))

(def ^{:doc "ResultSet keyword constants" :static true}
  resultset-options
  {:forward-only       ResultSet/TYPE_FORWARD_ONLY    ;; Type
   :scroll-insensitive ResultSet/TYPE_SCROLL_INSENSITIVE
   :scroll-sensitive   ResultSet/TYPE_SCROLL_SENSITIVE

   ;; Cursors
   :hold               ResultSet/HOLD_CURSORS_OVER_COMMIT
   :close              ResultSet/CLOSE_CURSORS_AT_COMMIT

   ;; Concurrency
   :read-only          ResultSet/CONCUR_READ_ONLY
   :updatable          ResultSet/CONCUR_UPDATABLE})

(def ^{:doc "Transaction isolation levels" :static true}
  isolation-levels
  {:none             Connection/TRANSACTION_NONE
   :read-uncommitted Connection/TRANSACTION_READ_UNCOMMITTED
   :read-committed   Connection/TRANSACTION_READ_COMMITTED
   :repeatable-read  Connection/TRANSACTION_REPEATABLE_READ
   :serializable     Connection/TRANSACTION_SERIALIZABLE})



