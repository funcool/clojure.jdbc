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

(ns jdbc.core
  "Alternative implementation of jdbc wrapper for clojure."
  (:require [clojure.string :as str]
            [jdbc.types :as types]
            [jdbc.impl :as impl]
            [jdbc.proto :as proto]
            [jdbc.resultset :refer [result-set->lazyseq result-set->vector]]
            [jdbc.transaction :as tx]
            [jdbc.constants :as constants])
  (:import java.sql.PreparedStatement
           java.sql.ResultSet
           java.sql.Connection))

(def ^{:doc "Default transaction strategy implementation."
       :no-doc true
       :dynamic true}
  *default-tx-strategy* (impl/transaction-strategy))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main public api.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn connection
  "Creates a connection to a database. As parameter accepts:

  - dbspec map containing connection parameters
  - dbspec map containing a datasource (deprecated)
  - URI or string (interpreted as uri)
  - DataSource instance

  The dbspec map has this possible variants:

  Classic approach:

  - `:subprotocol` -> (required) string that represents a vendor name (ex: postgresql)
  - `:subname` -> (required) string that represents a database name (ex: test)
    (many others options that are pased directly as driver parameters)

  Pretty format:

  - `:vendor` -> (required) string that represents a vendor name (ex: postgresql)
  - `:name` -> (required) string that represents a database name (ex: test)
  - `:host` -> (optional) string that represents a database hostname (default: 127.0.0.1)
  - `:port` -> (optional) long number that represents a database port (default: driver default)
    (many others options that are pased directly as driver parameters)

  URI or String format: `vendor://user:password@host:post/dbname?param1=value`

  Additional options:

  - `:schema` -> string that represents a schema name (default: nil)
  - `:read-only` -> boolean for mark entire connection read only.
  - `:isolation-level` -> keyword that represents a isolation level (`:none`, `:read-committed`,
                        `:read-uncommitted`, `:repeatable-read`, `:serializable`)

  Opions can be passed as part of dbspec map, or as optional second argument.
  For more details, see documentation."
  ([dbspec] (connection dbspec {}))
  ([dbspec options]
   (let [^Connection conn (proto/connection dbspec)
         options (merge (when (map? dbspec) dbspec) options)]

     ;; Set readonly flag if it found on the options map
     (some->> (:read-only options)
              (.setReadOnly conn))

     ;; Set the concrete isolation level if it found
     ;; on the options map
     (some->> (:isolation-level options)
              (get constants/isolation-levels)
              (.setTransactionIsolation conn))

     ;; Set the schema if it found on the options map
     (some->> (:schema options)
              (.setSchema conn))

     (let [tx-strategy (:tx-strategy options  *default-tx-strategy*)
           metadata {:tx-strategy tx-strategy}]
       (with-meta (types/->connection conn) metadata)))))

(defn prepared-statement?
  "Check if specified object is prepared statement."
  [obj]
  (instance? PreparedStatement obj))

(defn prepared-statement
  "Given a string or parametrized sql in sqlvec format
  return an instance of prepared statement."
  ([conn sqlvec] (prepared-statement conn sqlvec {}))
  ([conn sqlvec options]
   (let [conn (proto/connection conn)]
     (proto/prepared-statement sqlvec conn options))))

(defn execute
  "Execute a query and return a number of rows affected.

      (with-open [conn (jdbc/connection dbspec)]
        (jdbc/execute conn \"create table foo (id integer);\"))

  This function also accepts sqlvec format."
  ([conn q] (execute conn q {}))
  ([conn q opts]
   (let [rconn (proto/connection conn)]
     (proto/execute q rconn opts))))

(defn fetch
  "Fetch eagerly results executing a query.

  This function returns a vector of records (default) or
  rows (depending on specified opts). Resources are relased
  inmediatelly without specific explicit action for it.

  It accepts a sqlvec, plain sql or prepared statement
  as query parameter."
  ([conn q] (fetch conn q {}))
  ([conn q opts]
   (let [rconn (proto/connection conn)]
     (proto/fetch q rconn opts))))

(defn fetch-one
  "Fetch eagerly one restult executing a query."
  ([conn q] (fetch-one conn q {}))
  ([conn q opts]
   (first (fetch conn q opts))))

(defn fetch-lazy
  "Fetch lazily results executing a query.

      (with-open [cursor (jdbc/fetch-lazy conn sql)]
        (doseq [item (jdbc/cursor->lazyseq cursor)]
          (do-something-with item)))

  This function returns a cursor instead of result.
  You should explicitly close the cursor at the end of
  iteration for release resources."
  ([conn q] (fetch-lazy conn q {}))
  ([conn q opts]
   (let [^Connection conn (proto/connection conn)
         ^PreparedStatement stmt (proto/prepared-statement q conn opts)]
     (types/->cursor stmt))))

(def ^{:doc "Deprecated alias for backward compatibility."
       :deprecated true}
  lazy-query fetch-lazy)

(defn cursor->lazyseq
  "Transform a cursor in a lazyseq.

  The returned lazyseq will return values until a cursor
  is closed or all values are fetched."
  ([cursor] (impl/cursor->lazyseq cursor {}))
  ([cursor opts] (impl/cursor->lazyseq cursor opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn atomic-apply
  "Wrap function in one transaction.
  This function accepts as a parameter a transaction strategy. If no one
  is specified, `DefaultTransactionStrategy` is used.

  With `DefaultTransactionStrategy`, if current connection is already in
  transaction, it uses truly nested transactions for properly handle it.
  The availability of this feature depends on database support for it.

      (with-open [conn (jdbc/connection)]
        (atomic-apply conn (fn [conn] (execute! conn 'DROP TABLE foo;'))))

  For more idiomatic code, you should use `atomic` macro.

  Depending on transaction strategy you are using, this function can accept
  additional parameters. The default transaction strategy exposes two additional
  parameters:

  - `:isolation-level` - set isolation level for this transaction
  - `:read-only` - set current transaction to read only"
  [conn func & [{:keys [savepoints strategy] :or {savepoints true} :as opts}]]
  (let [metadata (meta conn)
        tx-strategy (or strategy
                        (:tx-strategy metadata)
                        *default-tx-strategy*)]
    (when (and (:transaction metadata) (not savepoints))
      (throw (RuntimeException. "Savepoints explicitly disabled.")))

    (let [conn (proto/begin! tx-strategy conn opts)
          metadata (meta conn)]
      (try
        (let [returnvalue (func conn)]
          (proto/commit! tx-strategy conn opts)
          returnvalue)
        (catch Throwable t
          (proto/rollback! tx-strategy conn opts)
          (throw t))))))

(defmacro atomic
  "Creates a context that evaluates in transaction (or nested transaction).
  This is a more idiomatic way to execute some database operations in
  atomic way.

      (jdbc/atomic conn
        (jdbc/execute conn \"DROP TABLE foo;\")
        (jdbc/execute conn \"DROP TABLE bar;\"))

  Also, you can pass additional options to transaction:

      (jdbc/atomic conn {:read-only true}
        (jdbc/execute conn \"DROP TABLE foo;\")
        (jdbc/execute conn \"DROP TABLE bar;\"))
  "
  [conn & body]
  (if (map? (first body))
    `(let [func# (fn [c#] (let [~conn c#] ~@(next body)))]
       (atomic-apply ~conn func# ~(first body)))
    `(let [func# (fn [c#] (let [~conn c#] ~@body))]
       (atomic-apply ~conn func#))))

(defn set-rollback!
  "Mark a current connection for rollback.

  It ensures that on the end of the current transaction
  instead of commit changes, rollback them.

  This function should be used inside of a transaction
  block, otherwise this function does nothing.

      (jdbc/atomic conn
        (make-some-queries-without-changes conn)
        (jdbc/set-rollback! conn))
  "
  [conn]
  (let [metadata (meta conn)]
    (when-let [rollback-flag (:rollback metadata)]
      (reset! rollback-flag true))))
