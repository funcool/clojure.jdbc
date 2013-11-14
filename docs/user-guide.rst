==========
User Guide
==========


Database connection parameters
==============================

Usually, all documentation of any jvm languaje that explains jdbc, always suppose
that a reader comes from java and knowns well about jdbc. This documentation will
not make the same mistake.

jdbc is a default abstraction/interface for sql databases written in java. Is like
a python DB-API or any other abstraction in any languaje. Clojure as a guest language
on a jvm, is benefits of having a good and well tested abstraction like this.

`dbspec` is a simple clojure way to define database connection parameters that are
used to create a new database connection or create new datasource (connection pool).

This is a default aspect of one dbspec definition:

.. code-block:: clojure

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

.. code-block:: text

    "postgresql://user:password@localhost:5432/dbname"

And also, it has other format using datasource, but it explained in 'Connection pools'
section.


Execute database commands
=========================

clj.jdbc has many methods for execute database commands, like create tables, inserting
data or simply execute stored procedure.

Execute raw sql statements
--------------------------

The simplest way to execute a raw sql is using `execute!` function. It receives
a connection as first parameter and  one or more sql strings.

.. code-block:: clojure

    ;; Without transactions
    (with-connection dbspec conn
      (execute! conn "CREATE TABLE foo (id serial, name text);"))

    ;; In one transaction
    (with-connection dbspec conn
      (with-transaction conn
        (execute! conn "CREATE TABLE foo (id serial, name text);")))

Execute parametrized sql statements
-----------------------------------

Raw sql statements works well for creating tables and similar operations, but when
you need insert some data, specially if data comes from untrusted sources, `execute!`
function is not adecuate.

For this problem, clj.jdbc exposes `execute-prepared!` function. It accepts parametrized
sql an list of groups of parameters.

