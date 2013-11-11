(ns jdbc-test
  (:require [clojure.test :refer :all]
            [jdbc :refer :all]))

(def h2-dbspec1 {:classname "org.h2.Driver"
                 :subprotocol "h2"
                 :subname "jdbctest.db"})

(def h2-dbspec2 {:subprotocol "h2"
                 :subname "jdbctest.db"})

(def h2-dbspec3 {:subprotocol "h2"
                 :subname "mem"})

(deftest test-dbspec
  (testing "Connect using dbspec"
    (let [c (make-connection h2-dbspec1)]
      (is (instance? jdbc.Connection c)))
    (let [c (make-connection h2-dbspec2)]
      (is (instance? jdbc.Connection c)))
    (let [c (make-connection h2-dbspec3)]
      (is (instance? jdbc.Connection c)))))
