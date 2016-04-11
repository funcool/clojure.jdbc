(ns jdbc.core-tests
  (:import org.postgresql.util.PGobject)
  (:require [clojure.string :as s]
            [jdbc.core :as jdbc]
            [jdbc.proto :as proto]
            [hikari-cp.core :as hikari]
            [cheshire.core :as json]
            [clojure.test :refer :all]))

(def h2-dbspec1 {:classname "org.h2.Driver"
                 :subprotocol "h2"
                 :subname "/tmp/jdbctest.db"})

(def h2-dbspec2 {:subprotocol "h2"
                 :subname "/tmp/jdbctest.db"})

(def h2-dbspec3 {:subprotocol "h2"
                 :subname "mem:"})

(def h2-dbspec4 {:subprotocol "h2"
                 :subname "mem:"
                 :isolation-level :serializable})

(def pg-dbspec {:subprotocol "postgresql"
                :subname "//localhost:5432/test"})

(def pg-dbspec-pretty {:vendor "postgresql"
                       :name "test"
                       :host "localhost"
                       :read-only true})

(def pg-dbspec-uri-1 "postgresql://localhost:5432/test")
(def pg-dbspec-uri-2 "postgresql://localhost:5432/test?")
(def pg-dbspec-uri-3 "postgresql://localhost:5432/test?foo=bar")

(deftest datasource-spec
  (with-open [ds (hikari/make-datasource {:adapter "h2" :url "jdbc:h2:/tmp/test"})]
    (is (instance? javax.sql.DataSource ds))
    (with-open [conn (jdbc/connection ds)]
      (let [result (jdbc/fetch conn "SELECT 1 + 1 as foo;")]
        (is (= [{:foo 2}] result))))))

(deftest db-specs
  (let [c1 (jdbc/connection h2-dbspec1)
        c2 (jdbc/connection h2-dbspec2)
        c3 (jdbc/connection h2-dbspec3)
        c4 (jdbc/connection pg-dbspec-pretty)
        c5 (jdbc/connection pg-dbspec-uri-1)
        c6 (jdbc/connection pg-dbspec-uri-2)
        c7 (jdbc/connection pg-dbspec-uri-3)]
    (is (satisfies? proto/IConnection c1))
    (is (satisfies? proto/IConnection c2))
    (is (satisfies? proto/IConnection c3))
    (is (satisfies? proto/IConnection c4))
    (is (satisfies? proto/IConnection c5))
    (is (satisfies? proto/IConnection c6))
    (is (satisfies? proto/IConnection c7))))

(deftest db-isolation-level-1
  (let [c1 (-> (jdbc/connection h2-dbspec4)
               (proto/connection))
        c2 (-> (jdbc/connection h2-dbspec3)
               (proto/connection))]
    (is (= (.getTransactionIsolation c1) 8))
    (is (= (.getTransactionIsolation c2) 2))))

(deftest db-isolation-level-2
  (let [func1 (fn [conn]
                (let [conn (proto/connection conn)
                      isolation (.getTransactionIsolation conn)]
                  (is (= isolation 8))))]
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/atomic-apply conn func1 {:isolation-level :serializable}))))

(deftest db-readonly-transactions
  (letfn [(func [conn]
            (let [raw (proto/connection conn)]
              (is (true? (.isReadOnly raw)))))]
    (with-open [conn (jdbc/connection pg-dbspec)]
      (jdbc/atomic-apply conn func {:read-only true})
      (is (false? (.isReadOnly (proto/connection conn))))))

  (with-open [conn (jdbc/connection pg-dbspec)]
    (jdbc/atomic conn {:read-only true}
      (is (true? (.isReadOnly (proto/connection conn)))))
    (is (false? (.isReadOnly (proto/connection conn))))))

(deftest db-commands-2
  (with-open [conn (jdbc/connection pg-dbspec)]
    (jdbc/atomic conn
      (jdbc/set-rollback! conn)
      (jdbc/execute conn "create table foo (id serial, age integer);")
      (let [result (jdbc/fetch conn ["insert into foo (age) values (?) returning id" 1])]
        (is (= result [{:id 1}]))))))

(deftest db-commands
  ;; Simple statement
  (with-open [conn (jdbc/connection h2-dbspec3)]
    (let [sql "CREATE TABLE foo (name varchar(255), age integer);"
          r   (jdbc/execute conn sql)]
      (is (= (list 0) r))))

  ;; Statement with exception
  (with-open [conn (jdbc/connection h2-dbspec3)]
    (let [sql "CREATE TABLE foo (name varchar(255), age integer);"]
      (jdbc/execute conn sql)
      (is (thrown? org.h2.jdbc.JdbcBatchUpdateException (jdbc/execute conn sql)))))

  ;; Fetch from simple query
  (with-open [conn (jdbc/connection h2-dbspec3)]
    (let [result (jdbc/fetch conn "SELECT 1 + 1 as foo;")]
      (is (= [{:foo 2}] result))))


  ;; Fetch from complex query in sqlvec format
  (with-open [conn (jdbc/connection pg-dbspec)]
    (let [result (jdbc/fetch conn ["SELECT * FROM generate_series(1, ?) LIMIT 1 OFFSET 3;" 10])]
      (is (= (count result) 1))))

  (with-open [conn (jdbc/connection pg-dbspec)]
    (let [result (jdbc/fetch conn ["SELECT i % 2 = 1 AS odd FROM generate_series(1, ?) t(i);" 2]
                             {:metadata->identifiers (fn [metadata]
                                                       (->> metadata .getColumnCount inc
                                                            ;; 1 based indexing not 0
                                                            (range 1)
                                                            (map (fn [idx]
                                                                   (let [label (-> (.getColumnLabel metadata idx) s/lower-case (s/replace "_" "-"))]
                                                                     (-> (str label (when (= "bool" (.getColumnTypeName metadata idx)) "?"))
                                                                         keyword))))))})]
      (is (= [{:odd? true} {:odd? false}] result))))

  ;; Fetch with sqlvec format and overwriting identifiers parameter
  (with-open [conn (jdbc/connection h2-dbspec3)]
    (let [result (jdbc/fetch conn ["SELECT 1 + 1 as foo;"] {:identifiers identity})]
      (is (= [{:FOO 2}] result))))

  ;; Fetch returning rows
  (with-open [conn (jdbc/connection h2-dbspec3)]
    (let [result (jdbc/fetch conn ["SELECT 1 + 1 as foo;"] {:as-rows? true})]
      (is (= [2] (first result)))))

  ;; Fetch from prepared statement
  (with-open [conn (jdbc/connection h2-dbspec3)]
    (let [stmt (jdbc/prepared-statement conn ["select ? as foo;" 2])
          result (jdbc/fetch conn stmt)]
      (is (= [{:foo 2}] result)))))

(deftest lazy-queries
  (with-open [conn (jdbc/connection h2-dbspec3)]
    (jdbc/atomic conn
      (with-open [cursor (jdbc/fetch-lazy conn "SELECT 1 + 1 as foo;")]
        (let [result (vec (jdbc/cursor->lazyseq cursor))]
          (is (= [{:foo 2}] result)))
        (let [result (vec (jdbc/cursor->lazyseq cursor))]
          (is (= [{:foo 2}] result)))))))

(deftest insert-bytes
  (let [buffer       (byte-array (map byte (range 0 10)))
        inputStream  (java.io.ByteArrayInputStream. buffer)
        sql          "CREATE TABLE foo (id integer, data bytea);"]
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/execute conn sql)
      (let [res (jdbc/execute conn ["INSERT INTO foo (id, data) VALUES (?, ?);" 1 inputStream])]
        (is (= res 1)))
      (let [res (jdbc/fetch-one conn "SELECT * FROM foo")]
        (is (instance? (Class/forName "[B") (:data res)))
        (is (= (get (:data res) 2) 2))))))


(extend-protocol proto/ISQLType
  (class (into-array String []))
  (as-sql-type [this conn] this)
  (set-stmt-parameter! [this conn stmt index]
    (let [prepared (proto/as-sql-type this conn)
          array (.createArrayOf conn "text" prepared)]
      (.setArray stmt index array))))

(deftest insert-arrays
  (with-open [conn (jdbc/connection pg-dbspec)]
    (jdbc/atomic conn
      (jdbc/set-rollback! conn)
      (let [sql "CREATE TABLE arrayfoo (id integer, data text[]);"
            dat (into-array String ["foo", "bar"])]
        (jdbc/execute conn sql)
        (let [res (jdbc/execute conn ["INSERT INTO arrayfoo (id, data) VALUES (?, ?);" 1, dat])]
          (is (= res 1)))

        (let [res (jdbc/fetch-one conn "SELECT * FROM arrayfoo")
              rr (.getArray (:data res))]
          (is (= (count rr) 2))
          (is (= (get rr 0) "foo"))
          (is (= (get rr 1) "bar")))))))

(deftest transactions-dummy-strategy
  (let [sql1 "CREATE TABLE foo (name varchar(255), age integer);"
        sql2 "INSERT INTO foo (name,age) VALUES (?, ?);"
        sql3 "SELECT age FROM foo;"
        strategy (reify proto/ITransactionStrategy
                   (begin! [_ conn opts] conn)
                   (rollback! [_ conn opts] nil)
                   (commit! [_ conn opts] nil))
        dbspec (assoc h2-dbspec3 :tx-strategy strategy)]
    (with-open [conn (jdbc/connection dbspec)]
      (is (identical? (:tx-strategy (meta conn)) strategy))
      (jdbc/execute conn sql1)
      (try
        (jdbc/atomic conn
          (jdbc/execute conn [sql2 "foo" 1])
          (jdbc/execute conn [sql2 "bar" 2])
          (let [results (jdbc/fetch conn sql3)]
            (is (= (count results) 2))
            (throw (RuntimeException. "Fooo"))))

        (catch Exception e
          (let [results (jdbc/fetch conn sql3)]
            (is (= (count results) 2))))))))


(deftest transactions
  (let [sql1 "CREATE TABLE foo (name varchar(255), age integer);"
        sql2 "INSERT INTO foo (name,age) VALUES (?, ?);"
        sql3 "SELECT age FROM foo;"]

    ;; Basic transaction test with exception.
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/execute conn sql1)

      (try
        (jdbc/atomic conn
          (jdbc/execute conn [sql2 "foo" 1])
          (jdbc/execute conn [sql2 "bar" 2])

          (let [results (jdbc/fetch conn sql3)]
              (is (= (count results) 2))
              (throw (RuntimeException. "Fooo"))))
          (catch Exception e
            (let [results (jdbc/fetch conn sql3)]
              (is (= (count results) 0))))))

    ;; Basic transaction test without exception.
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/execute conn sql1)

      (jdbc/atomic conn
        (jdbc/execute conn [sql2 "foo" 1])
        (jdbc/execute conn [sql2 "bar" 2]))

        (jdbc/atomic conn
          (let [results (jdbc/fetch conn sql3)]
            (is (= (count results) 2)))))

    ;; Immutability
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/atomic conn
        (let [metadata (meta conn)]
          (is (:transaction metadata))
          (is (:rollback metadata))
          (is (false? @(:rollback metadata)))
          (is (nil? (:savepoint metadata)))))

      (let [metadata (meta conn)]
        (is (= (:transaction metadata) nil))
        (is (= (:rollback metadata) nil))))

    ;; Savepoints
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/atomic conn
        (is (:transaction (meta conn)))
        (jdbc/atomic conn
          (is (not (nil? (:savepoint (meta conn))))))))

    ;; Set rollback 01
    (with-open [conn (jdbc/connection h2-dbspec3)]
        (jdbc/execute conn sql1)

        (jdbc/atomic conn
        (jdbc/execute conn [sql2 "foo" 1])
        (jdbc/execute conn [sql2 "bar" 2])
        (is (false? @(:rollback (meta conn))))

        (jdbc/atomic conn
          (jdbc/execute conn [sql2 "foo" 1])
          (jdbc/execute conn [sql2 "bar" 2])
          (jdbc/set-rollback! conn)
          (is (true? @(:rollback (meta conn))))
          (let [results (jdbc/fetch conn sql3)]
            (is (= (count results) 4))))

        (let [results (jdbc/fetch conn [sql3])]
          (is (= (count results) 2)))))

    ;; Set rollback 02
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/execute conn sql1)

      (jdbc/atomic conn
        (jdbc/set-rollback! conn)
        (jdbc/execute conn [sql2 "foo" 1])
        (jdbc/execute conn [sql2 "bar" 2])

        (is (true? @(:rollback (meta conn))))

        (jdbc/atomic conn
          (is (false? @(:rollback (meta conn))))

          (jdbc/execute conn [sql2 "foo" 1])
          (jdbc/execute conn [sql2 "bar" 2])
          (let [results (jdbc/fetch conn sql3)]
            (is (= (count results) 4))))

        (let [results (jdbc/fetch conn [sql3])]
          (is (= (count results) 4))))

      (let [results (jdbc/fetch conn [sql3])]
        (is (= (count results) 0))))


    ;; Subtransactions
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/execute conn sql1)

      (jdbc/atomic conn
        (jdbc/execute conn [sql2 "foo" 1])
        (jdbc/execute conn [sql2 "bar" 2])

        (try
          (jdbc/atomic conn
            (jdbc/execute conn [sql2 "foo" 1])
            (jdbc/execute conn [sql2 "bar" 2])
            (let [results (jdbc/fetch conn [sql3])]
              (is (= (count results) 4))
              (throw (RuntimeException. "Fooo"))))
          (catch Exception e
            (let [results (jdbc/fetch conn [sql3])]
              (is (= (count results) 2)))))))
    ))
