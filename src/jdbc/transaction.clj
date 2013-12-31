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
  "Namespace that encapsules all transaction specific logic."
  (:require [jdbc.types.connection :refer [is-connection?]]))

(defprotocol ITransactionStrategy
  (begin [_ conn opts] "Starts a transaction")
  (rollback [_ conn opts] "Rollbacks a transaction")
  (commit [_ conn opts] "Commits a transaction"))

(defrecord DefaultTransactionStrategy []
  ITransactionStrategy
  (begin [_ conn opts]
    (let [raw-connection (:connection conn)
          conn           (assoc conn :rollback (atom false))]
      (if (:in-transaction conn)
        (assoc conn :savepoint (.setSavepoint raw-connection))
        (let [prev-autocommit-state (.getAutoCommit raw-connection)]
          (.setAutoCommit raw-connection false)
          (assoc conn :prev-autocommit-state prev-autocommit-state
                      :in-transaction true)))))
  (rollback [_ conn opts]
    (let [raw-connection        (:connection conn)
          savepoint             (:savepoint conn)
          prev-autocommit-state (:prev-autocommit-state conn)]
      (if savepoint
        (do
          (.rollback raw-connection savepoint)
          (dissoc conn :savepoint :rollback))
        (do
          (.rollback raw-connection)
          (.setAutoCommit raw-connection prev-autocommit-state)
          (dissoc conn :prev-autocommit-state :rollback)))))

  (commit [_ conn opts]
    (let [connection            (:connection conn)
          savepoint             (:savepoint conn)
          prev-autocommit-state (:prev-autocommit-state conn)]
      (if savepoint
        (do
          (.releaseSavepoint connection savepoint)
          (dissoc conn :savepoint :rollback))
        (do
          (.commit connection)
          (.setAutoCommit connection prev-autocommit-state)
          (dissoc conn :prev-autocommit-state :in-transaction :rollback))))))

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
    (deref rollback)
    false))

(defn call-in-transaction
  "Wrap function in one transaction.

  This function accepts as a parameter a transaction strategy. If no one
  is specified, ``DefaultTransactionStrategy`` is used.

  With ``DefaultTransactionStrategy``, if current connection is already in
  transaction, it uses truly nested transactions for properly handle it.
  The availability of this feature depends on database support for it.

  Example:

    (with-connection dbspec conn
      (call-in-transaction conn (fn [conn] (execute! conn 'DROP TABLE foo;'))))

  For more idiomatic code, you should use `with-transaction` macro.
  "
  [conn func & {:keys [savepoints strategy] :or {savepoints true} :as opts}]
  {:pre [(is-connection? conn)]}
  (let [conn-tx-strategy     (:transaction-strategy conn)
        transaction-strategy (cond
                                strategy strategy
                                conn-tx-strategy conn-tx-strategy
                               :else (DefaultTransactionStrategy.))]
    (when (and (:in-transaction conn) (not savepoints))
      (throw (RuntimeException. "Savepoints explicitly disabled.")))
    (let [conn (begin transaction-strategy conn opts)]
      (try
        (let [returnvalue (func conn)]
          (if (:rollback conn)
            (rollback transaction-strategy conn opts)
            (commit transaction-strategy conn opts))
          returnvalue)
        (catch Throwable t
          (rollback transaction-strategy conn opts)
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
      (execute! conn 'DROP TABLE bar;'))"
  [conn & body]
  `(let [func# (fn [c#] (let [~conn c#] ~@body))]
     (apply call-in-transaction [~conn func#])))
