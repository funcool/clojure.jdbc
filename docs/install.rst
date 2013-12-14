Install
=======

This part of the documentation covers the installation of **clj.jdbc** using abitual
tools of clojure ecosystem.

Leiningen
---------

Install and use clj.jdbc is simple as put the following line on your dependency
vector on ``project.clj``:

.. code-block:: clojure

    [be.niwi/clj.jdbc "0.1.0-beta4"]

Gradle
-------

If you are using gradle, this is a dependency line for gradle dsl:

.. code-block:: groovy

    compile "be.niwi:clj.jdbc:0.1.0-beta4"

Maven
-----

And for old school people, who are using a ugly xml files for configure everything,
this is a xml that you should put on dependency section on a maven config file:

.. code-block:: xml

    <dependency>
      <groupId>be.niwi</groupId>
      <artifactId>clj.jdbc</artifactId>
      <version>0.1.0-beta4</version>
    </dependency>

Get the Code
------------

**clj.jdbc** is developed on GitHub, where the code is `always available <https://github.com/niwibe/clj.jdbc>`_.

You can either clone the public repository:

.. code-block:: text

    git clone https://github.com/niwibe/clj.jdbc.git

