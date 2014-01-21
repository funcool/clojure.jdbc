===
FAQ
===

Why another jdbc wrapper?
===========================

- Connection management should be explicit. clj.jdbc has a clear differentiation
  between connection and dbspec without unnecessary nesting controls and with explicit
  resource management (using `with-open` or other specific macros for it, see the
  examples).

- clj.jdbc has full support for all the transactions api, with the ability to set the
  database isolation level and use nested transactions (savepoints).

  It creates a new transaction if no other transaction is active but,
  when invoked within the context of an already existing transaction, it creates a savepoint.

- clj.jdbc supports extend or overwrite a transaction management if a default
  behavior is not sufficient for you.

- clj.jdbc has native support for connection pools. It offers helpers fuctions
  for various implementations (c3p0 and bonecp), that convert a plain dbspec to
  a dbspec with a datasource attached.

- clj.jdbc has a simpler implementation than clojure.java.jdbc. It has no more
  complexity than necessary for each available function in public api.

  As an example:

  - clojure.java.jdbc has a lot boilerplate connection management around all functions
    that receives dbspec. It doesn't has well designed connection management.

    Ex: functions like `create!` can receive plain dbspec or a connection. If you are
    curious, take a look to `with-db-connection`_ implementation of clojure.java.jdbc
    and compare it with `with-connection`_ of clj.jdbc. You are going to give account of the
    hidden unnecesary complexity found on clojure.java.jdbc.

    clojure.java.jdbc has inconsistent connection management. In contrast, with clj.jdbc,
    a connection should be created explicitly befor use any other function that
    requires one connection.

  - clojure.java.jdbc has repeated transaction handling on each CRUD method
    (insert!, drop!, etc...). With clj.jdbc, if you want that some code to run in a
    transaction, you should wrap it in a transaction context explicitly, using the
    `with-transaction` macro (see the transactions section for more information).

- Much more documentation ;) (a project without documentation is a project that doesn't
  really exist).

.. _`with-db-connection`: https://github.com/clojure/java.jdbc/blob/master/src/main/clojure/clojure/java/jdbc.clj#L574
.. _`with-connection`: https://github.com/niwibe/clj.jdbc/blob/master/src/jdbc.clj#L397


Why clj.jdbc does not include dsl for working with sql as clojure.java.jdbc 0.3?
================================================================================

"Write programs that do one thing and do it well."

clj.jdbc is a wrapper for Java JDBC interface, it doesn't intend provide helpers
for avoid sql usage. There already are a good number of DSLs for working with SQL.
clj.jdbc will not reinvent the wheel.

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
