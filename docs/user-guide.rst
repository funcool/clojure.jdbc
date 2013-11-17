==========
User Guide
==========


Connecting to database
======================

Connection parameters
---------------------

Usually, the documentation of any jvm language that explains JDBC always
supposes that the reader comes from Java and knowns JDBC well. This
documentation will not make the same mistake.

JDBC is the default JAVA abstraction/interface for SQL databases.  It's like
the Python DB-API and similar abstractions in other languages.  Clojure, as a
guest language on the jvm, benefits from having a good, well tested abstraction
like this.

``dbspec`` is a simple Clojure way to define the database connection parameters
that are used to create a new database connection or create a new datasource
(connection pool).

This is a default aspect of one dbspec definition:

.. code-block:: clojure

    {:classname "org.postgresql.Driver"
     :subprotocol "postgresql"
     :subname "//localhost:5432/dbname"
     :user "username"
     :password "password"}

Description of the parameters:

- ``:classname`` This is a class location of JDBC driver. Each driver has one; in this example it is a path to a Postgresql JDBC driver.  This parameter can be omited;  in that case it is automatically resolved from a predefined list using ``:subprotocol``.
- ``:user`` and ``:password`` can be ommited if they are empty.

dbspec has other formats which are ultimately parsed to the previously explained format.
As an example, you can pass a string containing a url with same data:

.. code-block:: text

    "postgresql://user:password@localhost:5432/dbname"

Also, there is another format using datasource, but this it is explained in the
'Connection pools' section.


Creating a connection
---------------------

In clj.jdbc, every function that interacts with a database takes a connection
instance as parameter, in contrast to clojure.java.jdbc. clj.jdbc requires
connections to be explicitly opened.

For this purpose, clj.jdbc exposes two ways to create new connections:
then ``make-connection`` function and the ``with-connection`` macro.

The first way intends be a low level interface and delegates resource
management to the user (who has to close the connection once it is no longer
used, for example). The second way is a higher level interface:
``with-connection`` creates a context in which the connection is available and,
at the end of execution of the code within the context, the connection is
closed automatically.


.. note::

    If you use connection pools, connection is automatically returned to the pool.

This is a simple example of use ``make-connection``:

.. code-block:: clojure

    (let [conn (make-connection dbspec)]
      (do-something-with conn)
      (.close conn))

The Connection class implements the AutoClosable interface, so you can avoid
having to call the ``close`` method directly by using Clojure's ``with-open``
macro:

.. code-block:: clojure

    (with-open [conn (make-connection dbspec)]
      (do-something-with conn))

And this is an equivalent piece of code using the ``with-connection`` macro
that clj.jdbc provides:

.. code-block:: clojure

    (with-connection dbspec conn
      (do-something-with conn))


Execute database commands
=========================

clj.jdbc has many methods for executing database commands, like creating
tables, inserting data or simply executing stored procedures.

Execute raw sql statements
--------------------------

The simplest way to execute a raw SQL is using the ``execute!`` function. It
receives a connection as the first parameter, and one or more SQL strings.

.. code-block:: clojure

    ;; Without transactions
    (with-connection dbspec conn
      (execute! conn "CREATE TABLE foo (id serial, name text);"))

    ;; In one transaction
    (with-connection dbspec conn
      (with-transaction conn
        (execute! conn "CREATE TABLE foo (id serial, name text);")))

Execute parametrized SQL statements
-----------------------------------

Raw SQL statements work well for creating tables and similar operations, but
when you need to insert some data, especially if the data comes from untrusted
sources, the ``execute!`` function is not adecuate.

For this problem, clj.jdbc exposes the ``execute-prepared!`` function. It
accepts parametrized SQL and a list of groups of parameters.

To execute a simple insert SQL statement:

.. code-block:: clojure

    (let [sql "INSERT INTO foo VALUES (?, ?);"]
      (execute-prepared! conn sql ["Foo", 2]))

The `execute-prepared!` function can accept multiple param groups, that are
helpful for performing multiple inserts in a batch:

.. code-block:: clojure

    (let [sql "INSERT INTO foo VALUES (?, ?);"]
      (execute-prepared! conn sql ["Foo", 2] ["Bar", 3]))

The previous code should execute these SQL statements:

.. code-block:: sql

    INSERT INTO foo VALUES ('Foo', 2);
    INSERT INTO foo VALUES ('Bar', 3);

Make queries
============

As usual, clj.jdbc offers two ways to send queries to a database, a low level
and a high level way. In this case, the low level interface differs a litle
from the high level one, because it returns an intermediate object: an instance
of the ``QueryResult`` type, defined by clj.jdbc.

.. note::

    You can see the api documentation to know more about it, but mainly it is for mantaining a reference
    to the original java jdbc objects which were used for executing a query.

We will begin by explaining the high-level ``with-query`` macro for performing
queries. The simplest way of explaining how it works is by seeing some
examples:

.. code-block:: clojure

    (let [sql ["SELECT id, name FROM people WHERE age > ?", 2]]
      (with-query sql results
        (doseq [row results]
          (println row))))

``results`` is a var name where a ``with-query`` macro binds a lazy-seq with rows.

Futhermore, the low level function, as mentioned before, returns a QueryResult
instance that works as a Clojure map and contains three keys: ``:stmt``,
``:rs`` and ``:data``.

The value represented by the last key (``:data``) is the ``results`` of previous code.

If you know how jdbc works, you should know that if you execute two queries and
the second is executed while the results of the first haven't been completely
consumed, the results of the first query are aborted. For this purpose you
should use the ``make-query`` function with precaution.

This is a simple example of use for the ``make-query`` function:

.. code-block:: clojure

    (let [sql    ["SELECT id, name FROM people WHERE age > ?", 2]
          result (make-query conn sql)]
      (doseq [row (:data result)]
        (println row))
      (.close result))

QueryResult also implements the ``AutoClosable`` interface and you can use it
with ``with-open`` macro.

Another feature that ``make-query`` exposes that is not available on the
``with-query`` macro is that you can request a non-lazy ``:data`` rows seq:

.. code-block:: clojure

    (let [sql ["SELECT id,name FROM people WHERE age > ?", 2]]
      (with-open [result (make-query conn sql :lazy? false)]
        (println (vector? (:data result)))))

    ;; -> true

Transactions
============

Managing transactions well is almost the most important thing when building an
application, and delaying it to the end is not a good approach. Managing
transactions implicitly, trusting your "web framework" to do it for you, is
another very bad approach.

**clj.jdbc** offers (as usually) two ways of managing transactions: the
``with-transaction`` macro and the ``call-in-transaction`` function.

Making some code transactional (so that executes in one transaction) is as
simple as wrapping the code in a transaction context block:

.. code-block:: clojure

    (with-transaction conn
       (do-thing-first conn)
       (do-thing-second conn))

Or, alternatively, using the ``call-in-transaction`` function:

.. code-block:: clojure

    (call-in-transaction conn do-things)

**clj.jdbc** supports subtransactions well. As an example, if one of the
functions used in the previous examples itself wrapped some code in a
transaction block, clj.jdbc would automatically wrap it in one subtransaction
(savepoint), making all the code wrapped in a transaction truly atomic.


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
