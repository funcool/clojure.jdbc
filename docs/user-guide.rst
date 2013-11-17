==========
User Guide
==========


Connecting to database
======================

Connection parameters
---------------------

Usually, all documentation of any jvm languaje that explains jdbc, always suppose
that a reader comes from java and knowns well about jdbc. This documentation will
not make the same mistake.

jdbc is a default abstraction/interface for sql databases written in java. Is like
a python DB-API or any other abstraction in any languaje. Clojure as a guest language
on a jvm, is benefits of having a good and well tested abstraction like this.

``dbspec`` is a simple clojure way to define database connection parameters that are
used to create a new database connection or create new datasource (connection pool).

This is a default aspect of one dbspec definition:

.. code-block:: clojure

    {:classname "org.postgresql.Driver"
     :subprotocol "postgresql"
     :subname "//localhost:5432/dbname"
     :user "username"
     :password "password"}

Parameters description:

- ``:classname`` can be omited and it automatically resolved from predefined list using ``:subprotocol``.
  This is a class location of jdbc driver. Each driver has one, in this example is a path to a postgresql jdbc driver.
- ``:user`` and ``:password`` can be ommited if them are empty

Also, dbspec has other formats that finally parsed to a previously explained format.
As example you can pass a string containing a url with same data:

.. code-block:: text

    "postgresql://user:password@localhost:5432/dbname"

And also, it has other format using datasource, but it explained in 'Connection pools'
section.


Creating a connection
---------------------

Every function that interacts with database in clj.jdbc only accept a connection
instance in difference with clojure.java.jdbc. For it, a connection will be opened
explicitly.

For this purpose, clj.jdbc exposes two ways to create new connection: using ``make-connection``
function or ``with-connection`` macro.

The first function intends be a low level interface and delegates to user a resource
management (close a connection when is not used as example). And a second as a high level
interface. ``with-connection`` creates a context when connection is available and at the end
of context code execution, connection is closed automatically.


.. note::

    If you use connection pools, connection is automatically returned to the pool.

This is a simple example of use ``make-connection``:

.. code-block:: clojure

    (let [conn (make-connection dbspec)]
      (do-something-with conn)
      (.close conn))

Connection instance implements AutoClosable interface, and you can avoid call directly
to ``close`` method using clojure ``with-open`` macro:

.. code-block:: clojure

    (with-open [conn (make-connection dbspec)]
      (do-something-with conn))

And this is a equivalent code using ``with-connection`` macro that clj.jdbc provides:

.. code-block:: clojure

    (with-connection dbspec conn
      (do-something-with conn))


Execute database commands
=========================

clj.jdbc has many methods for execute database commands, like create tables, inserting
data or simply execute stored procedure.

Execute raw sql statements
--------------------------

The simplest way to execute a raw sql is using ``execute!`` function. It receives
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
you need insert some data, specially if data comes from untrusted sources, ``execute!``
function is not adecuate.

For this problem, clj.jdbc exposes ``execute-prepared!`` function. It accepts parametrized
sql an list of groups of parameters.

For execute a simple insert sql statement:

.. code-block:: clojure

    (let [sql "INSERT INTO foo VALUES (?, ?);"]
      (execute-prepared! conn sql ["Foo", 2]))

But `execute-prepared!` function accept multiple param groups, that can help for insert
a multiple inserts in batch:

.. code-block:: clojure

    (let [sql "INSERT INTO foo VALUES (?, ?);"]
      (execute-prepared! conn sql ["Foo", 2] ["Bar", 3]))

The previous code should execute this sql statements:

.. code-block:: sql

    INSERT INTO foo VALUES ('Foo', 2);
    INSERT INTO foo VALUES ('Bar', 3);

Make queries
============

As usual, clj.jdbc offers two ways to send queries to a database, low level and high level
way. In this case, a low level interface differs litle bit from hight level, because it returns
a intermediate object, instance of ``QueryResult`` type, defined by clj.jdbc.

.. note::

    You can see a api documentation for know more about it, but mainly is for mantain a reference
    to a original java jdbc objects that are used for execute a query.

In this case we start explain a high level macro ``with-query`` for make queries. The simplest way
to explain of how it works is seeing some examples:

.. code-block:: clojure

    (let [sql ["SELECT id, name FROM people WHERE age > ?", 2]]
      (with-query sql results
        (doseq [row results]
          (println row))))

``results`` is a var name where a ``with-query`` macro binds a lazy-seq with rows.

Futhermore, a low level function, as I have said before, returns a QueryResult instance
that works as clojure map and contains three keys: ``:stmt``, ``:rs`` and ``:data``.

The value that represents a last key (``:data``) is a ``results`` of previous code.

If you known's how jdbc works, I should know that if you execute two queries and the second is
executed when the results of first are not completelly consumed, results of first query are
aborted. For this purpose you should use with precaution a ``make-query`` function.

This is a simple example of use ``make-query`` function:

.. code-block:: clojure

    (let [sql    ["SELECT id, name FROM people WHERE age > ?", 2]
          result (make-query conn sql)]
      (doseq [row (:data result)]
        (println row))
      (.close result))

QueryResult also implements ``AutoClosable`` interface and you can use it with ``with-open``
macro.

Othe of the features that exposes ``make-query`` that is not available on ``with-query``
macro is that you can make a ``:data`` rows seq not lazy:

.. code-block:: clojure

    (let [sql ["SELECT id,name FROM people WHERE age > ?", 2]]
      (with-open [result (make-query conn sql :lazy? false)]
        (println (vector? (:data result)))))

    ;; -> true

Transactions
============

Manage well transactions is almost the most important this when you build one application, and
delay it for the last time is not a good approach. Managing transaction implicitly, trust your
"web framework" for do it is other very bad approach.

For this case, **clj.jdbc** offers (as usually) two ways to manage transactions. Using
``with-transaction`` macro or ``call-in-transaction`` function.

Make some code transactional (executes in one transaction) is as simple how wrap some code in
transaction context block:

.. code-block:: clojure

    (with-transaction conn
       (do-thing-first conn)
       (do-thing-second conn))

Or using ``call-in-transaction`` function:

.. code-block:: clojure

    (call-in-transaction conn do-things)

**clj.jdbc** supports well subtransactions. As example, if one of functions used in previous
examples also wraps some code in transaction block. clj.jdbc automatically wrapps it in
one subtransaction (savepoints) making all code wrapped in a transaction truly atomic.


Isolation Level
---------------

clj.jdbc by default does nothing with isolation level and keep it with default values. But
provides a simple way to use a specific isolation level if a user requires it.

You have two ways to change a isolation level. Setting it on your dbspec or setting
programatically a globally default that will be applied automatically on each new created
connection.

As example, each connection created with this dbspec automatically set
a isolation level to SERIALIZABLE:

.. code-block:: clojure

    (def dbsoec {:subprotocol "h2"
                 :subname "mem:"
                 :isolation-level :serializable})

Also, clj.jdbc provides a simple function ``set-default-isolation-level!`` that you can
use, to set it globally:

.. code-block:: clojure

    (set-default-isolation-level! :read-commited)

This is a list of supported options:

- ``:read-commited`` - Set read committed isolation level
- ``:repeatable-read`` - Set repeatable reads isolation level
- ``:serializable`` - Set serializable isolation level
- ``:none`` - Use this option to indicate to clj.jdbc to do nothing and keep default behavior.

You can read more about it on wikipedia_.

.. _wikipedia: http://en.wikipedia.org/wiki/Isolation_(database_systems)


Connection pool
===============

clj.jdbc by default goes with connection pools helpers. And, as all of things in clj.jdbc,
if you need a connection pool you should do it explicitly.

Java ecosystem comes with various connection pool implementations for jdbc and clj.jdbc
add helpers for one of this: c3p0 (in near future will surely be implemented helpers for
other implementations).

A simple way to start using a connection pool is convert your plain dbspec with database
connection parameters to dbspec with datasource instance:

.. code-block:: clojure

    (require '[jdbc.pool.c3p0 :as pool])

    (def dbspec (pool/make-datasource-spec {:classname "org.postgresql.Driver"
                                            :subprotocol "postgresql"
                                            :subname "//localhost:5432/dbname"}))

``dbspec`` now contains a ``:datasource`` key with ``javax.sql.DataSource`` instance as value
instead of plain dbspec with database connection parameters. And it can be used as
dbspec for create connection with ``with-connection`` macro or ``make-connection`` function.
