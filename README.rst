clj.jdbc
========

Alternative implementation of jdbc wrapper for clojure.

Install
-------

Leiningen

.. code-block:: clojure

    [be.niwi/clj.jdbc "0.1.0-beta1"]

Gradle

.. code-block:: groovy

    compile "be.niwi:clj.jdbc:0.1.0-beta1"

Maven

.. code-block:: xml

    <dependency>
      <groupId>be.niwi</groupId>
      <artifactId>clj.jdbc</artifactId>
      <version>0.1.0-beta1</version>
    </dependency>


Doc
---

**Documentation:** http://cljjdbc.readthedocs.org/en/latest/

**ApiDoc:** http://niwibe.github.io/clj.jdbc/api/

Todo:
-----

- helper for work with binary data
- helper for create table.
- helper for drop table.
- access to more low level options of connection, resultset or statement.


Changelog
---------

Version 0.1-beta2
~~~~~~~~~~~~~~~~~

Date: 2013/11/25

- Remove some taken code from clojure.java.jdbc
  that are licensed under epl.
- Add ability to set the isolation level.
- Add new ``query`` function.
- Change default behavior for querying a database: now the default
  behavior is evaluate a request because of all jdbc implementation
  retrieves all resulset in memory and use lazy-seq is totally useless.

Version 0.1-beta1
~~~~~~~~~~~~~~~~~

Date: 2013/11/14

- Initial relase
