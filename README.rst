clj.jdbc
========

Alternative implementation of jdbc wrapper for clojure.

Install
-------

Leiningen

.. code-block:: clojure

    [be.niwi/clj.jdbc "0.1.0-beta4"]

Gradle

.. code-block:: groovy

    compile "be.niwi:clj.jdbc:0.1.0-beta4"

Maven

.. code-block:: xml

    <dependency>
      <groupId>be.niwi</groupId>
      <artifactId>clj.jdbc</artifactId>
      <version>0.1.0-beta4</version>
    </dependency>


Doc
---

**Documentation:** http://cljjdbc.readthedocs.org/en/latest/

**ApiDoc:** http://niwibe.github.io/clj.jdbc/api/


Changelog
---------

Version 0.1-beta5
~~~~~~~~~~~~~~~~~

Date: unreleased

- Add ability to extend all behavior between some type is set as parameter to prepared statement
  and retrieved from resultset thanks to ``ISQLType`` and ``ISQLResultSetReadColumn`` (this allows
  extend not default types, making it compatible for pass as paramater to jdbc and extend sql types for
  automatically convert them to custom types when are retrieved from resultset).
- Refactored jdbc.types namespace (rename QueryResult to ResultSet and put each type in their own
  namespace and add additional util functions for each type).
- Move all transactions logic to separate namespace, removing it from a main jdbc namespace (breaking change).
- Rename ``execute-statement`` to ``execute-statement!`` for more concise function naming.
- Remove untested and unused ``execute-statement->query-result`` function.
- ``make-query``, ``query`` and ``with-query`` now accepts prepared statements as parameter instead of
  plain or parametrized sql.


Version 0.1-beta4
~~~~~~~~~~~~~~~~~

Date: 2013-12-14

- Now transaction management is extensible. ITransactionStrategy is exposed and DefaultTransactionStrategy
  is a default implementation that cases with previous transaction behavior. If you want other transaction
  strategy, just implement ITransactionStrategy protocol and pass it to ``call-in-transaction`` function.

- Custom sql types now supported. Extend your type with ISQLType protocol and implement ``as-sql-type``
  function for it, that should return database compatible type.

- Backward incompatible change: ``mark-as-rollback-only!``, ``unmark-rollback-only!`` and ``is-rollback-only?``
  are renamed to more concise names: ``set-rollback!``, ``unset-rollback!`` and ``is-rollback-set?``

- Rollback behavior changed. Now rollback functions only affects a current transaction or subtransaction and
  it never interferes in parent transactions.

- Ensuers inmutablity on connection instance on transaction blocks. Now transaction blocks has only one
  clear defined side-effect: commit/rollback/setAutoCommit operations. Rollback flag is more limited
  side-effect that only change state of connection for current transaction.

- Simplify isolation level setting. Now only can set isolation level on dbspec or on creating connection.
  All global state is removed.

Version 0.1-beta3
~~~~~~~~~~~~~~~~~

Date: 2013-12-08

- Minor code cleaning and function name consistency fixes.
- Expose more private functions as public.
- Fix wrong preconditions and some bugs introduced in previos version.
- Add more tests.

Version 0.1-beta2
~~~~~~~~~~~~~~~~~

Date: 2013-11-25

- Remove some taken code from clojure.java.jdbc
  that are licensed under epl.
- Add ability to set the isolation level.
- Add new ``query`` function.
- Change default behavior for querying a database: now the default
  behavior is evaluate a request because of all jdbc implementation
  retrieves all resulset in memory and use lazy-seq is totally useless.

Version 0.1-beta1
~~~~~~~~~~~~~~~~~

Date: 2013-11-14

- Initial relase


.. image:: https://d2weczhvl823v0.cloudfront.net/niwibe/clj.jdbc/trend.png
   :alt: Bitdeli badge
   :target: https://bitdeli.com/free

