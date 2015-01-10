(ns jdbc.bench
  (:require [jdbc.core :as clojure.jdbc]
            [jdbc.transaction :as tx1]
            [clojure.java.jdbc :as java.jdbc]
            [criterium.core :refer [bench quick-bench]])
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
       (clojure.jdbc/query conn sql)))))

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
       (clojure.jdbc/query conn sql)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark 3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

  (with-open [conn (clojure.jdbc/connection dbspec)]
    (quick-bench
     (dotimes [i *iterations*]
       (tx1/with-transaction conn
         (clojure.jdbc/query conn sql))))))

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
  ;; (bench-03-with-transactions))
