(ns jdbc.types
  (:gen-class))

(defrecord Connection [connection in-transaction rollback-only]
  java.lang.AutoCloseable
  (close [this]
    (.close (:connection this))))

(defrecord QueryResult [stmt rs data]
  java.lang.AutoCloseable
  (close [this]
    (.close (:rs this))
    (.close (:stmt this))))
