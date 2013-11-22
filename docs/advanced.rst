Advanced usage
==============

.. _cursor_queries:

Server Side Cursors
-------------------

If you have an extremely large result set to retrieve from your database, or you would like to iterate through
a tables records without first retrieving the entire table a cursor queries is exactly what you need.

.. note::

    By default, most of jdbc drivers prefetches all results in memory that make totally useless use lazy
    structures for fetching data. For solve this, some databases implements server side cursors
    that avoids a prefetch all results of a quiery in memory.

For this purpose, exists ``with-query`` macro that hace some special behavior for querying large set
of data. Example:

.. code-block:: clojure

    (let [sql ["SELECT id, name FROM people;"]]
      (with-query sql results
        (doseq [row results]
          (println row))))

.. note::

    ``with-query`` macro implicitly ensures that all of code executed insinde a created
    context, are executed on transaction or subtransaction. This is mandatory because a
    server side cursors only works inside one transaction.


Low level query interface
-------------------------


All functions that finally executes a query, uses a ``make-query`` function, that is a low
level interface for access to query functionallity. This function has distinct behavior in
comparison with his high level siblings: returns a ``QueryResult`` instance that works
as clojure persistent map that contains these keys:

- ``:stmt`` key contains a statement instance used for make a query
- ``:rs`` key contains a raw ``java.sql.ResultSet`` instance
- ``:data`` key contains a real results as lazy-seq or vector

.. note::

    You can see the api documentation to know more about it, but mainly it is
    a container that mantains a reference  to the original java jdbc objects
    which are used for executing a query.

.. note::

    If you know how jdbc works, you should know that if you execute two queries and
    the second is executed while the results of the first haven't been completely
    consumed, the results of the first query are aborted.

    For this purpose you should use the ``make-query`` function with precaution.


This is a simple example of use for the ``make-query`` function:

.. code-block:: clojure

    (let [sql    ["SELECT id, name FROM people WHERE age > ?", 2]
          result (make-query conn sql)]
      (doseq [row (:data result)]
        (println row))
      (.close result))

QueryResult also implements the ``AutoClosable`` interface and you can use it
with ``with-open`` macro.

.. _connection-pool:

Connection pool
---------------

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
