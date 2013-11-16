Database specific notes
=======================

PostgreSQL
----------

This is a sample dbspec for postgresql

.. code-block:: clojure

    {:classname "org.postgresql.Driver"
     :subprotocol "postgresql"
     :subname "//localhost:5432/dbname"
     :user "username"
     :password "password"}


This an other example to connect to postgreql that uses a raw string that is passed
directly to postgresql driver:

.. code-block:: clojure

    {:connection-uri "jdbc:postgresql://localhost/test?ssl=true"}

.. note::

    This notation is jdbc driver dependent and not compatible with connection
    pool dbspec converters.

For more information, you can see a postgresql_ documentation for see a complete
set of options.

.. _postgresql: http://jdbc.postgresql.org/documentation/92/connect.html


H2
--

Here some examples of how create dbspec for h2 database. For see a complete list of possible
options, you can se a h2_ documentation.

In memory:
~~~~~~~~~~

.. code-block:: clojure

    ;; Private
    {:subprotocol "h2"
     :subname "mem:"})

    ;; Named
    {:subprotocol "h2"
     :subname "mem:dbname"})


Using a file:
~~~~~~~~~~~~~

.. code-block:: clojure

    {:classname "org.h2.Driver"
     :subprotocol "h2"
     :subname "file:/tmp/jdbctest.db"})

Using a ``:connection-uri`` way:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: clojure

    {:connection-uri "jdbc:h2:mem:test_mem"}

.. _h2: http://www.h2database.com/html/features.html#database_url


.. note::

    If you are missing examples for your database, send me a pull-request
    and I'm gladly will include your improvements.
