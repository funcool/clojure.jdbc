Advanced usage
==============

.. _cursor_queries:

Server Side Cursors
-------------------

By default, most of jdbc drivers prefetches all results in memory that make totally useless use lazy
structures for fetching data. For solve this, some databases implements server side cursors that
avoids a prefetch all results of a query in memory.

If you have an extremely large result set to retrieve from your database, is exactly what you need.

**clj.jdbc**, for this purpose, has ``with-query`` macro that uses server side cursors inside
and exposes a lazy seq of records (instead of full evaluated vector) in a created macro context:

.. code-block:: clojure

    (let [sql ["SELECT id, name FROM people;"]]
      (with-query conn sql results
        (doseq [row results]
          (println row))))

.. note::

    ``with-query`` macro implicitly ensures that all of code executed insinde a created
    context, are executed on transaction or subtransaction. This is mandatory because a
    server side cursors only works inside one transaction.


Low level query interface
-------------------------

All functions that finally executes a query, uses a ``make-query`` function, that is a low
level interface for access to query functionallity.

This function has distinct behavior in comparison with his high level siblings: it returns a
``jdbc.types.resultset.ResultSet`` instance that works as clojure persistent map that
contains these keys:

- ``:stmt`` key contains a statement instance used for make a query.
- ``:rs`` key contains a raw ``java.sql.ResultSet`` instance.
- ``:data`` key contains a real results as lazy-seq or vector depending on parameters.

.. note::

    You can see the api documentation to know more about it, but mainly it is
    a container that mantains a reference  to the original java jdbc objects
    which are used for executing a query.

.. note::

    If you know how jdbc works, you should know that if you execute two queries and
    the second is executed while the results of the first haven't been completely
    consumed, the results of the first query are aborted.

    For this purpose you should use the ``make-query`` function with precaution.


This is a simple example of use ``make-query`` function:

.. code-block:: clojure

    (let [sql    ["SELECT id, name FROM people WHERE age > ?", 2]
          result (make-query conn sql)]
      (doseq [row (:data result)]
        (println row))
      (.close result))

ResultSet also implements the ``java.lang.AutoClosable`` interface and you can use it
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


.. _transaction-strategy:

Transaction strategy
--------------------

.. versionadded:: 0.1-beta4

clj.jdbc transaction management is very flexible and accepts user customizations.

Default transaction management is implemented on ``DefaultTransactionStrategy`` record (that implements
``ITransactionStrategy`` protocol). If you want change the default behavior or reimplement it, you should
define yout record or type that should implement ``ITransactionStrategy`` protocol.

The ``ITransactionStrategy`` protocol is very simple, and cosist on these three methods: ``begin``, ``commit``
and ``rollback``.

.. This is a simple example that imitates a clojure.java.jdbc behavior (all subtransactions are grouped in
.. a first transaction):

This is a simple dummy transaction strategy that disables all transaction management:

.. code-block:: clojure

    (defrecord DummyTransactionStrategy []
      ITransactionStrategy
      (begin [_ conn opts] conn)
      (rollback [_ conn opts] conn)
      (commit [_ conn opts] conn))


And it can be used in these ways:

.. code-block:: clojure

    (with-connection dbspec conn
      (with-transaction-strategy conn (DummyTransactionStrategy.)
        (do-some-thing conn)))


This is a same example but using more low level interface:

.. code-block:: clojure

    (with-open [conn (-> (make-connection dbspec)
                         (wrap-transaction-strategy (DummyTransactionStrategy.)))]
      (do-some-thing conn))


Extend sql types
----------------

.. versionadded:: 0.1-beta4

.. versionchanged:: 0.1-beta5

    Allow backward conversions (sqltype to user type) and move all to wheir own namespace.

All related to type handling/conversion are exposed on ``jdbc.types`` namespace.

If you want extend some type/class for use it as jdbc parameter without explicit conversion
to sql compatible type, you should extend your type with ``jdbc.types/ISQLType`` protocol.

This is a sample example to extend a java String[] (string array) for pass it as parameter
to database field that correspons to postgresql text array on a database:

.. code-block:: clojure

    (extend-protocol ISQLType

      ;; Obtain a class for string array
      (class (into-array String []))

      (set-stmt-parameter! [this conn stmt index]
        (let [raw-conn        (:connection conn)
              prepared-value  (as-sql-type this conn)
              array           (.createArrayOf raw-conn "text" prepared-value)]
          (.setArray stmt index array)))

      (as-sql-type [this conn] this))


Now, you can pass a string array as jdbc parameter that is automaticlay converted
to sql array and assigned properly to prepared statement:

.. code-block:: clojure

    (with-connection pg-dbspec conn
      (execute! conn "CREATE TABLE arrayfoo (id integer, data text[]);")
      (let [mystringarray (into-array String ["foo" "bar"])]
        (execute-prepared! conn "INSERT INTO arrayfoo VALUES (?, ?);"
                           [1, mystringarray])))


clj.jdbc also exposes ``jdbc.types/ISQLResultSetReadColumn`` protocol that encapsulates
a backward conversions from sql types to user defined types.

Example:

TODO


Detailed documentation for ``ISQLType`` methods
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

- ``as-sql-type`` converts a extended type to sql type. Default implementation return a object as is.
- ``set-stmt-parameter!`` encapsulates logic for extended type of how set self as parameter to
  prepared statement. By default it uses ``setObject`` prepared statement method.

Detailed documention for ``ISQLResultSetReadColumn`` methods
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

- ``from-sql-type`` encapsulates logic for convert extended type to some specific (not sql) type.
