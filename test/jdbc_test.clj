(ns jdbc-test
  (:require [jdbc :refer :all]
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

(deftest db-specs
  (testing "Create connection with distinct dbspec"
    (let [c1 (make-connection h2-dbspec1)
          c2 (make-connection h2-dbspec2)
          c3 (make-connection h2-dbspec3)]
      (is (instance? jdbc.types.Connection c1))
      (is (instance? jdbc.types.Connection c2))
      (is (instance? jdbc.types.Connection c3))))

  (testing "Using macro with-connection"
    (with-connection h2-dbspec3 conn
      (is (instance? jdbc.types.Connection conn))))

  (testing "Utils functions"
    (is (= (strip-jdbc-prefix "jdbc:fobar") "fobar"))))

(deftest db-isolation-level
  (testing "Set/Unset isolation level"
    (is (= (deref *default-isolation-level*) :none))
    (set-default-isolation-level! :serializable)
    (is (= (deref *default-isolation-level*) :serializable))
    (set-default-isolation-level! :none)
    (is (= (deref *default-isolation-level*) :none))
    (is (thrown? AssertionError (set-default-isolation-level! :foobar))))
  (testing "Using dbspec with :isolation-level"
    (let [c1 (make-connection h2-dbspec4)
          c2 (make-connection h2-dbspec3)]
      (is (= (:isolation-level c1) :serializable))
      (is (= (:isolation-level c2) :none))))
  (testing "Create connection with modified default isolation level"
    (set-default-isolation-level! :repeatable-read)
    (let [c1 (make-connection h2-dbspec4)
          c2 (make-connection h2-dbspec3)]
      (is (= (:isolation-level c1) :serializable))
      (is (= (:isolation-level c2) :repeatable-read)))
    (set-default-isolation-level! :none)))

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

  (testing "Simple query result"
    (with-connection h2-dbspec3 conn
      (with-query conn results ["SELECT 1 + 1 as foo;"]
        (is (= [{:foo 2}] (doall results))))))

  (testing "Low level query result"
    (with-open [conn    (make-connection h2-dbspec3)
                result  (make-query conn ["SELECT 1 + 1 as foo;"])]
      (is (instance? jdbc.types.QueryResult result))
      (is (instance? java.sql.ResultSet (:rs result)))
      (is (instance? java.sql.PreparedStatement (:stmt result)))
      (is (instance? clojure.lang.PersistentVector (:data result)))
      (is (= [{:foo 2}] (doall (:data result))))))

  (testing "Low level query result with lazy off"
    (with-open [conn    (make-connection h2-dbspec3)
                result  (make-query conn ["SELECT 1 + 1 as foo;"] :lazy? false)]
      (is (instance? jdbc.types.QueryResult result))
      (is (instance? java.sql.ResultSet (:rs result)))
      (is (instance? java.sql.PreparedStatement (:stmt result)))
      (is (vector? (:data result)))
      (is (= [{:foo 2}] (doall (:data result))))))

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


(deftest db-pool
  (testing "C3P0 connection pool testing."
    (let [spec (pool-c3p0/make-datasource-spec h2-dbspec3)]
      (is (instance? javax.sql.DataSource (:datasource spec)))
      (with-open [conn (make-connection spec)]
        (is (instance? jdbc.types.Connection conn))
        (is (instance? java.sql.Connection (:connection conn)))))))


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
