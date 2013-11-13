(ns jdbc.pool.c3p0
  (:require [jdbc.pool :refer [normalize-dbspec]])
  (:import (com.mchange.v2.c3p0 ComboPooledDataSource)))

(defn make-datasource-spec
  "Given a plain dbspec, convert it on datasource dbspec
  using c3p0 connection pool implementation."
  [dbspec]
  (let [normalized (normalize-dbspec dbspec)]
    (when (or (:factory normalized)
              (:connection-uri normalized))
      (throw (RuntimeException. "Can not create connection pool from dbspec with factory or connection-url")))
    (when (:datasource normalized)
      (throw (RuntimeException. "Already have datasource.")))
    {:datasource (doto (ComboPooledDataSource.)
                   (.setDriverClass (:classname normalized))
                   (.setJdbcUrl (str "jdbc:"
                                     (:subprotocol normalized) ":"
                                     (:subname normalized)))
                   (.setUser (:user normalized))
                   (.setPassword (:password normalized))
                   (.setMaxIdleTimeExcessConnections (:excess-timeout normalized (* 30 60)))
                   (.setMaxIdleTime (:idle-timeout normalized (* 3 60 60)))
                   (.setMinPoolSize (:min-pool-size normalized 3))
                   (.setMaxPoolSize (:max-pool-size normalized 15))
                   (.setIdleConnectionTestPeriod (:idle-connection-test-period normalized 0))
                   (.setTestConnectionOnCheckin (:test-connection-on-checkin normalized false))
                   (.setTestConnectionOnCheckout (:test-connection-on-checkout normalized false))
                   (.setPreferredTestQuery (:test-connection-query normalized nil)))}))
