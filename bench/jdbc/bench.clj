(ns jdbc.bench
  (:require [jdbc.core :as clojure.jdbc]
            [jdbc.proto :as proto]
            [jdbc.transaction :as tx]
            [clojure.java.jdbc :as java.jdbc]
            [criterium.core :refer [bench quick-bench]])
  (:import java.sql.Connection)
  (:gen-class))

(def dbspec {:subprotocol "h2"
             :subname "mem:"})
(def sql "select * from system_range(0, 100);")

(def ^:dynamic *iterations* 1000)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark 1
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-1-java-jdbc
  []
  (println)
  (println "Benchmark: One query without connection overhead.")
  (println "Results for java.jdbc:")

  (java.jdbc/with-db-connection [conn dbspec]
    (quick-bench
     (dotimes [i *iterations*]
       (java.jdbc/query conn sql)))))

(defn bench-1-clojure-jdbc
  []
  (println)
  (println "Benchmark: One query without connection overhead.")
  (println "Results for clojure.jdbc:")

  (with-open [conn (clojure.jdbc/connection dbspec)]
    (quick-bench
     (dotimes [i *iterations*]
       (clojure.jdbc/fetch conn sql)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark 2
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-2-java-jdbc
  []
  (println)
  (println "Benchmark: One query with connection overhead.")
  (println "Results for java.jdbc:")

  (quick-bench
   (dotimes [i *iterations*]
     (java.jdbc/with-db-connection [conn dbspec]
       (java.jdbc/query conn sql)))))

(defn bench-2-clojure-jdbc
  []
  (println)
  (println "Benchmark: One query with connection overhead.")
  (println "Results for clojure.jdbc:")

  (quick-bench
   (dotimes [i *iterations*]
     (with-open [conn (clojure.jdbc/connection dbspec)]
       (clojure.jdbc/fetch conn sql)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark 3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def basic-tx-strategy
  (reify
    proto/ITransactionStrategy
    (begin! [_ conn opts]
      (let [^Connection rconn (proto/connection conn)
            metadata (meta conn)
            ^long depth (:depth-level metadata)]
        (if depth
          (with-meta conn
            (assoc metadata :depth-level (inc depth)))

          (let [prev-autocommit (.getAutoCommit rconn)]
            (.setAutoCommit rconn false)
            (with-meta conn
              (assoc metadata
                     :depth-level 0
                     :prev-autocommit prev-autocommit))))))

    (rollback! [_ conn opts]
      (let [^Connection rconn (proto/connection conn)
            metadata (meta conn)
            ^long depth (:depth-level metadata)]
        (when (= depth 0)
          (.rollback rconn)
          (.setAutoCommit rconn (:prev-autocommit metadata)))))

    (commit! [_ conn opts]
      (let [^Connection rconn (proto/connection conn)
            metadata (meta conn)
            ^long depth (:depth-level metadata)]
        (when (= depth 0)
          (.commit rconn)
          (.setAutoCommit rconn (:prev-autocommit metadata)))))))

(defn bench-3-java-jdbc
  []
  (println)
  (println "Benchmark: Simple query in a transaction without connection overhead")
  (println "Results for java.jdbc:")

  (java.jdbc/with-db-connection [conn dbspec]
    (quick-bench
     (dotimes [i *iterations*]
       (java.jdbc/with-db-transaction [conn conn]
         (java.jdbc/query conn sql))))))


(defn bench-3-clojure-jdbc
  []
  (println)
  (println "Benchmark: Simple query in a transaction without connection overhead")
  (println "Results for clojure.jdbc:")

  (with-open [conn (clojure.jdbc/connection (assoc dbspec :tx-strategy basic-tx-strategy))]
    (quick-bench
     (dotimes [i *iterations*]
       (clojure.jdbc/atomic conn
         (clojure.jdbc/fetch conn sql))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn -main
  [& args]
  (bench-1-java-jdbc)
  (bench-1-clojure-jdbc)

  (bench-2-java-jdbc)
  (bench-2-clojure-jdbc)

  (bench-3-java-jdbc)
  (bench-3-clojure-jdbc)
)
