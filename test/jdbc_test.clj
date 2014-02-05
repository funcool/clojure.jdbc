(ns jdbc-test
  (:require [jdbc :refer :all]
            [jdbc.transaction :refer :all]
            [jdbc.types :refer :all]
            [jdbc.pool.c3p0 :as pool-c3p0]
            [clojure.test :refer :all]))

(def h2-dbspec1 {:classname "org.h2.Driver"
                 :subprotocol "h2"
                 :subname "jdbctest.db"})

(def h2-dbspec2 {:subprotocol "h2"
                 :subname "jdbctest.db"})

(def h2-dbspec3 {:subprotocol "h2"
                 :subname "mem:"})

(def h2-dbspec4 {:subprotocol "h2"
                 :subname "mem:"
                 :isolation-level :serializable})

(def pg-dbspec {:classname "org.postgresql.Driver"
                :subprotocol "postgresql"
                :subname "//localhost:5432/test"})

(deftest db-specs
  (testing "Create connection with distinct dbspec"
    (let [c1 (make-connection h2-dbspec1)
          c2 (make-connection h2-dbspec2)
          c3 (make-connection h2-dbspec3)]
      (is (instance? jdbc.types.connection.Connection c1))
      (is (instance? jdbc.types.connection.Connection c2))
      (is (instance? jdbc.types.connection.Connection c3))))

  (testing "Using macro with-connection"
    (with-connection h2-dbspec3 conn
      (is (instance? jdbc.types.connection.Connection conn)))))

(deftest db-isolation-level
  (testing "Using dbspec with :isolation-level"
    (let [c1 (make-connection h2-dbspec4)
          c2 (make-connection h2-dbspec3)]
      (is (= (:isolation-level c1) :serializable))
      (is (= (:isolation-level c2) :none)))))

(deftest db-commands
  (testing "Simple create table"
    (with-connection h2-dbspec3 conn
      (let [sql "CREATE TABLE foo (name varchar(255), age integer);"
            r   (execute! conn sql)]
        (is (= (list 0) r)))))

  (testing "Create duplicate table"
     (with-connection h2-dbspec3 conn
       (let [sql "CREATE TABLE foo (name varchar(255), age integer);"]
         (execute! conn sql)
         (is (thrown? org.h2.jdbc.JdbcBatchUpdateException (execute! conn sql))))))

  (testing "Simple query result using with-query macro"
    (with-connection h2-dbspec3 conn
      (with-query conn results ["SELECT 1 + 1 as foo;"]
        (is (= [{:foo 2}] (doall results))))))

  (testing "Simple query result using query function"
    (with-connection h2-dbspec3 conn
      (let [result (query conn ["SELECT 1 + 1 as foo;"])]
        (is (= [{:foo 2}] result)))))

  (testing "Simple query result using query function overwriting identifiers parameter."
    (with-connection h2-dbspec3 conn
      (let [result (query conn ["SELECT 1 + 1 as foo;"] {:identifiers identity})]
        (is (= [{:FOO 2}] result)))))

  (testing "Simple query result using query function and string parameter"
    (with-connection h2-dbspec3 conn
      (let [result (query conn "SELECT 1 + 1 as foo;")]
        (is (= [{:foo 2}] result)))))

  (testing "Simple query result using query function as vectors of vectors"
    (with-connection h2-dbspec3 conn
      (let [result (query conn ["SELECT 1 + 1 as foo;"] {:as-rows? true})]
        (is (= [2] (first result))))))

  (testing "Pass prepared statement."
    (with-connection h2-dbspec3 conn
      (let [stmt    (make-prepared-statement conn ["SELECT 1 + 1 as foo;"])
            result  (query conn stmt {:as-rows? true})]
        (is (= [2] (first result))))))

  (testing "Low level query result"
    (with-open [conn    (make-connection h2-dbspec3)
                result  (make-query conn ["SELECT 1 + 1 as foo;"])]
      (is (instance? jdbc.types.resultset.ResultSet result))
      (is (instance? java.sql.ResultSet (:rs result)))
      (is (instance? java.sql.PreparedStatement (:stmt result)))
      (is (vector? (:data result)))
      (is (= [{:foo 2}] (doall (:data result))))))

  (testing "Low level query result with lazy off"
    (with-open [conn    (make-connection h2-dbspec3)]
      (with-transaction conn
        (let [result  (make-query conn ["SELECT 1 + 1 as foo;"] {:lazy true})]
          (is (instance? jdbc.types.resultset.ResultSet result))
          (is (instance? java.sql.ResultSet (:rs result)))
          (is (instance? java.sql.PreparedStatement (:stmt result)))
          (is (seq? (:data result)))
          (is (= [{:foo 2}] (doall (:data result))))))))

  (testing "Execute prepared"
    (with-connection h2-dbspec3 conn
      (execute! conn "CREATE TABLE foo (name varchar(255), age integer);")
      (execute-prepared! conn "INSERT INTO foo (name,age) VALUES (?, ?);"
                         ["foo", 1]  ["bar", 2])

      (with-query conn results ["SELECT count(age) as total FROM foo;"]
        (is (= [{:total 2}] (doall results)))))))

(deftest db-execute-statement
  (testing "Statement result"
    (with-connection h2-dbspec3 conn
      (execute! conn "CREATE TABLE foo (name varchar(255), age integer);")
      (let [res (execute-prepared! conn "INSERT INTO foo (name,age) VALUES (?, ?);"
                                   ["foo", 1]  ["bar", 2])]
        (is (= res (seq [1 1])))))))

(deftest db-commands-bytes
  (testing "Insert bytes"
    (let [buffer       (byte-array (map byte (range 0 10)))
          inputStream  (java.io.ByteArrayInputStream. buffer)
          sql          "CREATE TABLE foo (id integer, data bytea);"]
      (with-connection h2-dbspec3 conn
        (execute! conn sql)
        (let [res (execute-prepared! conn "INSERT INTO foo (id, data) VALUES (?, ?);" [1 inputStream])]
          (is (= res '(1))))

        (let [res (query conn "SELECT * FROM foo")
              res (first res)]
          (is (instance? (Class/forName "[B") (:data res)))
          (is (= (get (:data res) 2) 2)))))))

(extend-protocol ISQLType
  (class (into-array String []))

  (set-stmt-parameter! [this conn stmt index]
    (let [raw-conn        (:connection conn)
          prepared-value  (as-sql-type this conn)
          array           (.createArrayOf raw-conn "text" prepared-value)]
      (.setArray stmt index array)))

  (as-sql-type [this conn] this))

(deftest db-commands-custom-types
  (testing "Test use arrays"
    (with-connection pg-dbspec conn
      (with-transaction conn
        (let [sql "CREATE TABLE arrayfoo (id integer, data text[]);"
              dat (into-array String ["foo", "bar"])]
          (execute! conn sql)
          (let [res (execute-prepared! conn "INSERT INTO arrayfoo (id, data) VALUES (?, ?);" [1, dat])]
            (is (= res '(1))))
          (let [res (first (query conn "SELECT * FROM arrayfoo"))]

            (let [rr (.getArray (:data res))]
              (is (= (count rr) 2))
              (is (= (get rr 0) "foo"))
              (is (= (get rr 1) "bar")))))))))

(deftest db-pool
  (testing "C3P0 connection pool testing."
    (let [spec (pool-c3p0/make-datasource-spec h2-dbspec3)]
      (is (instance? javax.sql.DataSource (:datasource spec)))
      (with-open [conn (make-connection spec)]
        (is (instance? jdbc.types.connection.Connection conn))
        (is (instance? java.sql.Connection (:connection conn)))))))

(defrecord BasicTransactionStrategy []
  ITransactionStrategy
  (begin [_ conn opts]
    (let [depth    (:depth-level conn)
          raw-conn (:connection conn)]
      (if depth
        (assoc conn :depth-level (inc (:depth-level conn)))
        (let [prev-autocommit-state (.getAutoCommit raw-conn)]
          (.setAutoCommit raw-conn false)
          (assoc conn :depth-level 0 :prev-autocommit-state prev-autocommit-state)))))

  (rollback [_ conn opts]
    (let [depth    (:depth-level conn)
          raw-conn (:connection conn)]
      (if (= depth 0)
        (do
          (.rollback raw-conn)
          (.setAutoCommit raw-conn (:prev-autocommit-state conn))
          (dissoc conn :depth-level :prev-autocommit-state))
        conn)))

  (commit [_ conn opts]
    (let [depth    (:depth-level conn)
          raw-conn (:connection conn)]
      (if (= depth 0)
        (do
          (.commit raw-conn)
          (.setAutoCommit raw-conn (:prev-autocommit-state conn))
          (dissoc :depth-level :prev-autocommit-state))
        conn))))

(defrecord DummyTransactionStrategy []
  ITransactionStrategy
  (begin [_ conn opts] conn)
  (rollback [_ conn opts] conn)
  (commit [_ conn opts] conn))

(deftest db-transaction-strategy
  (let [sql1 "CREATE TABLE foo (name varchar(255), age integer);"
        sql2 "INSERT INTO foo (name,age) VALUES (?, ?);"
        sql3 "SELECT age FROM foo;"]
    (testing "Test dummy transaction strategy"
      (with-connection h2-dbspec3 conn
        (with-transaction-strategy conn (DummyTransactionStrategy.)
          (is (instance? DummyTransactionStrategy (:transaction-strategy conn)))
          (execute! conn sql1)
          (try
            (with-transaction conn
              (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
              (let [results (query conn sql3)]
                (is (= (count results) 2))
                (throw (RuntimeException. "Fooo"))))
            (catch Exception e
              (let [results (query conn sql3)]
                (is (= (count results) 2))))))))
    (testing "Test simple transaction strategy"
      (with-open [conn (-> (make-connection h2-dbspec3)
                           (wrap-transaction-strategy (BasicTransactionStrategy.)))]
        (is (instance? BasicTransactionStrategy (:transaction-strategy conn)))
        (execute! conn sql1)
        (try
          (with-transaction conn
            (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (is (= (:depth-level conn) 0))
            (with-transaction conn
              (is (= (:depth-level conn) 1))
              (let [results (query conn sql3)]
                (is (= (count results) 2))
                (throw (RuntimeException. "Fooo")))))
          (catch Exception e
            (let [results (query conn sql3)]
              (is (= (count results) 0)))))))))


(deftest db-transactions
  (let [sql1 "CREATE TABLE foo (name varchar(255), age integer);"
        sql2 "INSERT INTO foo (name,age) VALUES (?, ?);"
        sql3 "SELECT age FROM foo;"]

    (testing "Basic transaction test"
      (with-connection h2-dbspec3 conn
        (execute! conn sql1)

        (try
          (with-transaction conn
            (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (with-query conn results [sql3]
              (is (= (count results) 2))
              (throw (RuntimeException. "Fooo"))))
          (catch Exception e
            (with-query conn results [sql3]
              (is (= (count results) 0)))))))

    (testing "Immutability"
      (with-connection h2-dbspec3 conn
        (with-transaction conn
          (is (:in-transaction conn))
          (is (:rollback conn))
          (is (false? @(:rollback conn)))
          (is (nil? (:savepoint conn))))
        (is (= (:in-transaction conn) nil))
        (is (= (:rollback conn) nil))))

    (testing "Set savepoint"
      (with-connection h2-dbspec3 conn
        (with-transaction conn
          (is (:in-transaction conn))
          (with-transaction conn
            (is (not= (:savepoint conn) nil))))))

    (testing "Set rollback 01"
      (with-connection h2-dbspec3 conn
        (execute! conn sql1)

        (with-transaction conn
          (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
          (is (false? @(:rollback conn)))

          (with-transaction conn
            (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (set-rollback! conn)

            (is (true? @(:rollback conn)))

            (let [results (query conn sql3)]
              (is (= (count results) 4))))

          (with-query conn results [sql3]
            (is (= (count results) 2))))))

    (testing "Set rollback 02"
      (with-connection h2-dbspec3 conn
        (execute! conn sql1)

        (with-transaction conn
          (set-rollback! conn)
          (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])

          (is (true? @(:rollback conn)))

          (with-transaction conn
            (is (false? @(:rollback conn)))

            (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (let [results (query conn sql3)]
              (is (= (count results) 4))))

          (with-query conn results [sql3]
            (is (= (count results) 2))))

        (with-query conn results [sql3]
            (is (= (count results) 0)))))

    (testing "Subtransactions"
      (with-connection h2-dbspec3 conn
        (execute! conn sql1)

        (with-transaction conn
          (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])

          (try
            (with-transaction conn
              (execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
              (with-query conn results [sql3]
                (is (= (count results) 4))
                (throw (RuntimeException. "Fooo"))))
            (catch Exception e
              (with-query conn results [sql3]
                (is (= (count results) 2))))))))))
