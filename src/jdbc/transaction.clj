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

(ns jdbc.transaction
  "Transactions support for clj.jdbc"
  (:require [jdbc.types.connection :refer [is-connection?]]
            [jdbc.constants :as constants])
  (:import java.sql.Connection))

(defprotocol ITransactionStrategy
  (begin! [_ conn opts] "Starts a transaction and return a connection instance")
  (rollback! [_ conn opts] "Rollbacks a transaction. Returns nil.")
  (commit! [_ conn opts] "Commits a transaction. Returns nil."))

(defrecord DefaultTransactionStrategy []
  ITransactionStrategy
  (begin! [_ conn opts]
    (let [^Connection raw-conn      (:connection conn)
                      conn          (assoc conn :rollback (atom false))
                      old-isolation (.getTransactionIsolation raw-conn)
                      old-readonly  (.isReadOnly raw-conn)]
      (if (:in-transaction conn)
        ;; If connection is already in a transaction, isolation
        ;; level and read only flag can not to be changed.
        (do
          (when (:isolation-level opts)
            (throw (RuntimeException. "Can not set isolation level in transaction")))
          (when (:read-only opts)
            (throw (RuntimeException. "Can not change read only in transaction")))
          (assoc conn :savepoint (.setSavepoint raw-conn)))

        (let [old-autocommit (.getAutoCommit raw-conn)]
          (.setAutoCommit raw-conn false)
          (when-let [isolation (:isolation-level opts)]
            (.setTransactionIsolation raw-conn (get constants/isolation-levels isolation)))
          (when-let [read-only (:read-only opts)]
            (.setReadOnly raw-conn read-only))

          (assoc conn :old-autocommit old-autocommit
                      :old-isolation old-isolation
                      :old-readonly old-readonly
                      :in-transaction true
                      :isolation-level (or (:isolation-level opts)
                                           (:isolation-level conn)))))))
  (rollback! [_ conn opts]
    (let [^Connection raw-conn       (:connection conn)
                      savepoint      (:savepoint conn)
                      old-readonly   (:old-readonly conn)
                      old-isolation  (:old-isolation conn)
                      old-autocommit (:old-autocommit conn)]

      (if savepoint (.rollback raw-conn savepoint)
        (do
          (.rollback raw-conn)
          (.setAutoCommit raw-conn old-autocommit)
          (.setTransactionIsolation raw-conn old-isolation)
          (.setReadOnly raw-conn old-readonly)))))

  (commit! [_ conn opts]
    (let [^Connection raw-conn       (:connection conn)
                      savepoint      (:savepoint conn)
                      old-readonly   (:old-readonly conn)
                      old-isolation  (:old-isolation conn)
                      old-autocommit (:old-autocommit conn)]
      (if savepoint (.releaseSavepoint raw-conn savepoint)
        (do
          (.commit raw-conn)
          (.setAutoCommit raw-conn old-autocommit)
          (.setTransactionIsolation raw-conn old-isolation)
          (.setReadOnly raw-conn old-readonly))))))

(defn wrap-transaction-strategy
  "Simple helper function that associate a strategy
  to a connection and return a new connection object
  with wrapped stragy.

  Example:

    (let [conn (wrap-transaction-strategy simplecon (MyStrategy.))]
      (use-your-new-conn conn))
  "
  [conn strategy]
  (assoc conn :transaction-strategy strategy))

(defn set-rollback!
  "Mark a current connection for rollback.

  It ensures that on the end of the current transaction
  instead of commit changes, rollback them.

  This function should be used inside of a transaction
  block, otherwise this function does nothing.

  Example:

    (with-transaction conn
      (make-some-queries-without-changes conn)
      (set-rollback! conn))"
  [conn]
  {:pre [(is-connection? conn)]}
  (when-let [rollback-flag (:rollback conn)]
    (swap! rollback-flag (fn [_] true))))

(defn unset-rollback!
  "Revert flag setted by `set-rollback!` function.

  This function should be used inside of a transaction
  block, otherwise this function does nothing."
  [conn]
  {:pre [(is-connection? conn)]}
  (when-let [rollback-flag (:rollback conn)]
    (swap! rollback-flag (fn [_] false))))

(defn is-rollback-set?
  "Check if a current connection in one transaction
  is marked for rollback.

  This should be used in one transaction, in other case this
  function always return false.
  "
  [conn]
  {:pre [(is-connection? conn)]}
  (if-let [rollback-flag (:rollback conn)]
    (deref rollback-flag)
    false))

(defn call-in-transaction
  "Wrap function in one transaction.

This function accepts as a parameter a transaction strategy. If no one
is specified, ``DefaultTransactionStrategy`` is used.

With `DefaultTransactionStrategy`, if current connection is already in
transaction, it uses truly nested transactions for properly handle it.
The availability of this feature depends on database support for it.

Example:

(with-connection dbspec conn
  (call-in-transaction conn (fn [conn] (execute! conn 'DROP TABLE foo;'))))

For more idiomatic code, you should use `with-transaction` macro.

Depending on transaction strategy you are using, this function can accept
additional parameters. The default transaction strategy exposes two additional
parameters:

- `:isolation-level` - set isolation level for this transaction
- `:read-only` - set current transaction to read only
"
  [conn func & [{:keys [savepoints strategy] :or {savepoints true} :as opts}]]
  {:pre [(is-connection? conn)]}
  (let [tx-strategy (or strategy
                        (:transaction-strategy conn)
                        (DefaultTransactionStrategy.))]
    (when (and (:in-transaction conn) (not savepoints))
      (throw (RuntimeException. "Savepoints explicitly disabled.")))
    (let [conn (begin! tx-strategy conn opts)]
      (try
        (let [returnvalue (func conn)]
          (if @(:rollback conn)
            (rollback! tx-strategy conn opts)
            (commit! tx-strategy conn opts))
          returnvalue)
        (catch Throwable t
          (rollback! tx-strategy conn opts)
          (throw t))))))

(defmacro with-transaction-strategy
  "Set some transaction strategy connection in the current context
  scope.

  This method not uses thread-local dynamic variables and
  connection preserves a transaction strategy throught threads."
  [conn strategy & body]
  `(let [~conn (wrap-transaction-strategy ~conn ~strategy)]
     ~@body))

(defmacro with-transaction
  "Creates a context that evaluates in transaction (or nested transaction).

This is a more idiomatic way to execute some database operations in
atomic way.

Example:

(with-transaction conn
  (execute! conn 'DROP TABLE foo;')
  (execute! conn 'DROP TABLE bar;'))

Also, you can pass additional options to transaction:

(with-transaction conn {:read-only true}
  (execute! conn 'DROP TABLE foo;')
  (execute! conn 'DROP TABLE bar;'))
"
  [conn & body]
  (if (map? (first body))
    `(let [func# (fn [c#] (let [~conn c#] ~@(next body)))]
       (apply call-in-transaction [~conn func# ~(first body)]))
    `(let [func# (fn [c#] (let [~conn c#] ~@body))]
       (apply call-in-transaction [~conn func#]))))
