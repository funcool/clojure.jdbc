(ns jdbc.sql
  (:require [clojure.string :as str])
  (:gen-class))

(defn make-insert-stmt
  "Given a table name and records, returns a map with
  prepared sql and param groups. Ready for use in
  `execute-prepared!` function.

  Example:

    (make-insert-stmt :tablename
      {:name 'Foo' :age 20}
      {:name 'Bar' :age 30})

    This should return:

    {:stmt-sql 'INSERT INTO tablename (foo, age) VALUES (?,?);'
     :param-groups [['Foo' 20] ['Bar' 30]]}

  "
  [tablename & records]
  ;; TODO: check if all records has same keys
  (let [first-record  (first records)
        columns       (keys first-record)
        order-values  (fn [record]
                        (doall (map #(% record) columns)))
        final-sql     (format "INSERT INTO %s (%s) VALUES (%s);"
                              (name tablename)
                              (str/join "," (map name columns))
                              (str/join "," (repeat (count columns) "?")))]
    {:stmt-sql final-sql
     :param-groups (doall (map order-values records))}))
