(defproject be.niwi/jdbc "0.1.0"
  :description "Alternative implementation of jdbc wrapper for clojure.

For this momment, the official wrapper `clojure.jdbc` has very unclear
api and it doing a lot of implicit things such as connection management.

This library intends make more simple and clear api to jdbc than the
official library."
  :url "http://github.com/niwibe/cljdbc"
  :license {:name "Apache 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.txt"}
  :dependencies [[slingshot "0.10.3"]]
  ;;                [org.clojure/clojure "1.5.1"]]
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :test {:dependencies [[com.h2database/h2 "1.3.170"]
                                   [postgresql "9.1-901.jdbc4"]]}}
  ;; :main ^:skip-aot jdbc.core
  :target-path "target/%s")
