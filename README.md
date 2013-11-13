# clj.jdbc

Alternative implementation of jdbc wrapper for clojure.

## Introduction

clj.jdbc provides a simple abstraction for java jdbc interfaces supporting
all crud (create, read update, delete) operations on a SQL database, along
with basic transaction support.

Basic DDL operations will be  also supported in near future (create table,
drop table, access to table metadata).

Maps are used to represent records, making it easy to store and retrieve
data. Results can be processed using any standard sequence operations.

## Why one other jdbc wrapper?

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
    good example of complexity introduced with improperly connection management.<br /><br />
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

## DbSpecs or database connection parameters

Usually, all documentation of any jvm languaje that explains jdbc, always suppose
that a reader comes from java and knowns well about jdbc. This documentation will
not make the same mistake.

jdbc is a default abstraction/interface for sql databases written in java. Is like
a python DB-API or any other abstraction in any languaje. Clojure as a guest language
on a jvm, is benefits of having a good and well tested abstraction.

`dbspec` is a simple clojure way to define database connection parameters that are
used to create a new database connection or create new datasource (connection pool).

This is a default aspect of one dbspec definition:

    {:classname "org.postgresql.Driver"
     :subprotocol "postgresql"
     :subname "//localhost:5432/dbname"
     :user "username"
     :password "password"}

Parameters description:

- `:classname` can be omited and it automatically resolved from predefined list
   using `:subprotocol`. This is a class location of jdbc driver. Each driver has
   one, in this example is a path to a postgresql jdbc driver.
- `:user` and `:password` can be ommited if them are empty

Also, dbspec has other formats that finally parsed to a previously explained format.
As example you can pass a string containing a url with same data:

    "postgresql://user:password@localhost:5432/dbname"

And also, it has other format using datasource, but it explained in 'Connection pools'
section.

## Execute database commands

clj.jdbc has many methods for execute database commands, like create tables, inserting
data or simply execute stored procedure.

The simplest way to execute a raw sql is using `execute!` function. It receives
a connection as first parameter and  one or more sql strings.

```clojure
;; Without transactions
(with-connection dbspec conn
  (execute! conn 'CREATE TABLE foo (id serial, name text);'))

;; In one transaction
(with-connection dbspec conn
  (with-transaction conn
    (execute! conn 'CREATE TABLE foo (id serial, name text);')))
```
