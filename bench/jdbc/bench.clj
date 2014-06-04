(ns jdbc.bench
  (:require [jdbc :as j1]
            [jdbc.transaction :as tx1]
            [clojure.java.jdbc :as j2])
  (:gen-class))

(def dbspec {:classname "org.postgresql.Driver"
             :subprotocol "postgresql"
             :subname "//localhost:5432/test"})

(defn bench-01-without-connection-overhead
  []
  (println "Simple query without connection overhead.")
  (let [sql ["select * from generate_series(0, 100);"]]
    ;; Clojure Java JDBC
    (j2/with-db-connection [con-db dbspec]
      (let [f (fn []
                (dotimes [i 500]
                  (j2/query con-db sql)))]
        (println "java.jdbc:")
        (time (f))))

    ;; clojure.jdbc
    (j1/with-connection dbspec conn
      (let [f (fn []
                (dotimes [i 500]
                  (j1/query conn sql)))]
        (println "clojure.jdbc:")
        (time (f))))))

(defn bench-02-with-connection-overhead
  []
  (println "Simple query with connection overhead.")
  (let [sql ["select * from generate_series(0, 100);"]]
    ;; Clojure Java JDBC
    (let [f (fn []
              (dotimes [i 500]
                (j2/query dbspec sql)))]
      (println "java.jdbc:")
      (time (f)))

    ;; clojure.jdbc
    (let [f (fn []
              (dotimes [i 500]
                (j1/with-connection dbspec conn
                  (j1/query conn sql))))]
      (println "clojure.jdbc:")
      (time (f)))))

(defn bench-03-with-transactions
  []
  (println "Simple query with transaction.")
  (let [sql ["select * from generate_series(0, 100);"]]
    ;; Clojure Java JDBC
    (j2/with-db-connection [con-db dbspec]
      (let [f (fn []
                (dotimes [i 500]
                  (j2/with-db-transaction [con-db con-db]
                    (j2/query con-db sql))))]
        (println "java.jdbc:")
        (time (f))))

    ;; clojure.jdbc
    (j1/with-connection dbspec conn
      (let [f (fn []
                (dotimes [i 500]
                  (tx1/with-transaction conn
                    (j1/query conn sql))))]
        (println "clojure.jdbc:")
        (time (f))))))

(defn -main
  [& args]
  (bench-01-without-connection-overhead)
  (bench-02-with-connection-overhead)
  (bench-03-with-transactions))
