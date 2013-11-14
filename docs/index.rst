========
clj.jdbc
========

Alternative implementation of jdbc wrapper for clojure.

Introduction
============

clj.jdbc provides a simple abstraction for java jdbc interfaces supporting
all crud (create, read update, delete) operations on a SQL database, along
with basic transaction support.

Basic DDL operations will be  also supported in near future (create table,
drop table, access to table metadata).

Maps are used to represent records, making it easy to store and retrieve
data. Results can be processed using any standard sequence operations.


Documentation
=============

.. toctree::
    :maxdepth: 2

    intro
    user-guide
    faq



License
=======

.. code-block:: text

    Copyright 2013 Andrey Antukh <niwi@niwi.be>

    Licensed under the Apache License, Version 2.0 (the "License")
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
