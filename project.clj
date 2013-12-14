(defproject be.niwi/clj.jdbc "0.1.0-beta4"
  :description "Alternative implementation of jdbc wrapper for clojure.

For this momment, the official wrapper `clojure.jdbc` has very unclear
api and it doing a lot of implicit things such as connection management.

This library intends make more simple and clear api to jdbc than the
official library."
  :url "http://github.com/niwibe/clj.jdbc"
  :license {:name "Apache 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.txt"}
  :dependencies [[org.clojure/clojure "1.5.1"]]
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[org.clojure/clojure "1.5.1"]
                                  [com.mchange/c3p0 "0.9.5-pre5"]]}
             :test {:dependencies [[com.h2database/h2 "1.3.170"]
                                   [postgresql "9.1-901.jdbc4"]
                                   [com.mchange/c3p0 "0.9.5-pre5"]]}}

  ;; :main ^:skip-aot jdbc.core
  :plugins [[lein-marginalia "0.7.1"]
            [codox "0.6.6"]]
  :codox {:exclude [jdbc.types]
          :output-dir "docs/codox"
          :src-dir-uri "http://github.com/niwibe/clj.jdbc/blob/master/"
          :src-linenum-anchor-prefix "L"}
  :aot [jdbc.types]
  :target-path "target/%s")
