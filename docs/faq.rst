===
FAQ
===

Why another jdbc wrapper?
===========================

- Connection management should be explicit. clj.jdbc has a clear differentiation
  between connection and dbspec without unnecessary nesting controls and with explicit
  resource management (using `with-open` or other specific macros for it, see the
  examples).

- clj.jdb has full support for all the transactions api, with the ability to set the
  database isolation level and use nested transactions (savepoints).

  It creates a new transaction if no other transaction is active but,
  when invoked within the context of an already existing transaction, it creates a savepoint.

- clj.jdbc has native support for connection pools. It offers helpers fuctions
  for various implementations (c3p0 and bonecp), that convert a plain dbspec to
  a dbspec with a datasource attached.

- clj.jdbc has a simpler implementation than clojure.java.jdbc. It has no more
  complexity than necessary for each available function in public api.

  As an example:

  - clojure.java.jdbc has logic for connection nesting because it doesn't have proper
    connection management. Functions like `create!` can receive a plain dbspec or a dbspec
    with a created connection. If dbspec with active connection is received, it must
    increment a nesting value (this prevents prematurely closing the connection). This is a
    good example of complexity introduced because of improper connection management.

    With clj.jdbc, all the work done with a database should explicitly be
    wrapped in a connection context using the `with-connection` macro. This
    way, each function like `create!` can always be sure that it is going to
    receive a connection instance, removing connection handling from all
    functions.

  - clojure.java.jdbc has repeated transaction handling on each CRUD method
    (insert!, drop!, etc...). With clj.jdbc, if you want some code to run in a
    transaction, you should wrap it in a transaction context using the
    `with-transaction` macro (see the transactions section for more information).

- Many more examples of use for this api ;) (a project with no documentation
  is a project that doesn't really exist).


Why clj.jdbc does not include dsl for working with sql as clojure.java.jdbc 0.3?
================================================================================

Write programs that do one thing and do it well. clj.jdbc is a wrapper for Java
JDBC, not a wrapper and lot more things besides. There already are a good number
of DSLs for working with SQL. clj.jdbc will not reinvent the wheel.

One example of a good dsl for build sql: <https://github.com/r0man/sqlingvo>


This is a fork of clojure.java.jdbc?
====================================

No. Is just a alternative implementation.

How to contribute?
==================

**clj.jdbc** unlike clojure and other clojure contrib libs, does not have much
restrictions for contribute. Just follow the following steps depending on the
situation:

**Bugfix**:

- Fork github repo.
- Fix a bug/typo on new branch.
- Make a pull-request to master.

**New feature**:

- Open new issue with new feature purpose.
- If it is accepted, follow same steps as "bugfix".
