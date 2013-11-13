(ns jdbc.pool.c3p0
  (:use [slingshot.slingshot :only [throw+ try+]])
  (:require [jdbc.pool :refer [normalize-dbspec]])
  (:import (com.mchange.v2.c3p0 ComboPooledDataSource)))

(defn make-datasource-spec
  "This implements a dummy connection pool. Really does
  nothing and returns dbspec as is."
  ;; TODO: not finished
  [dbspec]
  (let [normalized (normalize-dbspec dbspec)]
    (when (or (:factory normalized)
              (:connection-uri normalized))
      (throw+ "Can not create connection pool from dbspec with factory or connection-url"))
    (when (:datasource normalized)
      (throw+ "Already have datasource."))
    {:datasource (doto (ComboPooledDataSource.)
                   (.setDriverClass (:classname normalized))
                   (.setJdbcUrl (str "jdbc:"
                                     (:subprotocol normalized) ":"
                                     (:subname normalized)))
                   (.setUser (:user normalized))
                   (.setPassword (:password normalized)))}))
