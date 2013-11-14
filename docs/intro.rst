===========================
Why one other jdbc wrapper?
===========================

- Connection management should be explicit. clj.jdbc has clear differentiation
  between connection and dbspec without uneccesary nesting controls and with explicit
  resource management (using `with-open` or other specific macros for it, see
  examples).

- clk.jdb has full support of all transaccions api, with ability to set database
  isolation level and use of nested transactions (savepoints).

  `with-transaction` macro works well with nested transactions using savepoints
  when it used as nested transaction. It ceates new transaction if no one transaction
  is active or savepoins in other case.

- clj.jdbc has native support for connection pools, having helpers for varios
  implementations (c3p0 and bonecp) for convert a plain dbspec to
  dbspec with datasource.

- clj.jdbc has simpler implementation than clojure.java.jdbc. It has no more complexity
  than necesary for each available function in public api.

  As example:

  - clojure.java.jdbc has logic for connection nestig because it hasn't have proper
    connection management. Functions like `create!` can receive plain dbspec or dbspec
    with crated connection. If dbspec with active connection is received, it should
    increment a nesting value (this prevents a close connection at finish). This is a
    good example of complexity introduced with improperly connection management.

    With clj.jdbc, all work with database should explicitly wrapped in connection
    context using `with-connection` macro. And each function like `create!` can
    suppose that always going to receive a connection instance, removing connection
    handling from all functions.

  - clojure.java.jdbc has repeated transaction handling on each crud method
    (insert!, drop!, etc...). With clj.jdbc, if you want that some code runs in a
    transaction, you should wrap it in a transaction context using
    `with-transaction` macro (see transactions section for more information).

- Much more examples of use this api ;) (project without documentation
  is project that does not exists).
