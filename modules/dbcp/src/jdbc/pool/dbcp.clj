;; Copyright 2014 Andrey Antukh <niwi@niwi.be>
;;
;; Licensed under the Apache License, Version 2.0 (the "License")
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns jdbc.pool.dbcp
  (:require [jdbc.pool :refer [normalize-dbspec]])
  (:import org.apache.commons.dbcp2.BasicDataSource))

(defn make-datasource-spec
  "Given a plain dbspec, convert it on datasource dbspec
  using apachr commons connection pool implementation."
  [dbspec]
  (let [normalized (normalize-dbspec dbspec)]
    (when (or (:factory normalized)
              (:connection-uri normalized))
      (throw (RuntimeException. "Can not create connection pool from dbspec with factory or connection-url")))
    (when (:datasource normalized)
      (throw (RuntimeException. "Already have datasource.")))
    {:datasource (doto (BasicDataSource.)
                   (.setDriverClassName (:classname normalized))
                   (.setUrl (str "jdbc:"
                                 (:subprotocol normalized) ":"
                                 (:subname normalized)))
                   (.setUsername (:user normalized))
                   (.setPassword (:password normalized))
                   (.setInitialSize (:initial-size normalized 0))
                   (.setMaxIdle (:max-idle normalized 3))
                   (.setMaxTotal (:max-total normalized 15))
                   (.setMinIdle (:min-idle normalized 0))
                   (.setMaxWaitMillis (:max-wait-millis normalized -1))

                   (.setTestOnCreate (:test-on-create normalized false))
                   (.setTestOnBorrow (:test-on-borrow normalized true))
                   (.setTestOnReturn (:test-on-return normalized false))
                   (.setTestWhileIdle (:test-while-idle normalized true))
                   (.setTimeBetweenEvictionRunsMillis (:time-between-eviction-runs-millis normalized -1))
                   (.setNumTestsPerEvictionRun (:num-tests-per-eviction-run normalized 3))
                   (.setMinEvictableIdleTimeMillis (:min-evictable-idle-time-millis normalized (* 1000 60 30)))
                   (.setMaxConnLifetimeMillis (:max-conn-lifetime-millis normalized -1))
                   (.setValidationQuery (:validation-query normalized nil)))}))


