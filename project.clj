(defproject clojure.jdbc "0.2.0b2"
  :description "Alternative implementation of jdbc wrapper for clojure.

For this momment, the official wrapper `clojure.jdbc` has very unclear
api and it doing a lot of implicit things such as connection management.

This library intends make more simple and clear api to jdbc than the
official library."
  :url "http://github.com/niwibe/clojure.jdbc"
  :license {:name "Apache 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.txt"}
  :dependencies [[org.clojure/clojure "1.6.0"]]
  :plugins [[lein-marginalia "0.7.1"]
            [codox "0.6.7"]
            [lein-sub "0.3.0"]]
  :codox {:output-dir "doc/api"
          :src-dir-uri "http://github.com/niwibe/clojure.jdbc/blob/master/"
          :src-linenum-anchor-prefix "L"}
  :sub ["modules/c3p0"
        "modules/dbcp"]
  :profiles {:bench {:source-paths ["bench/"]
                     :main jdbc.bench
                     :dependencies [[org.clojure/java.jdbc "0.3.3"]
                                    [postgresql "9.3-1101.jdbc41"]]}
             :test {:dependencies [[com.h2database/h2 "1.3.176"]
                                   [postgresql "9.3-1101.jdbc41"]
                                   [com.mchange/c3p0 "0.9.5-pre8"]
                                   [org.apache.commons/commons-dbcp2 "2.0.1"]
                                   [cheshire "5.3.1"]]
                    :source-paths ["modules/c3p0/src"
                                   "modules/dbcp/src"]
                    :jvm-opts ["-Dcom.mchange.v2.log.MLog=com.mchange.v2.log.FallbackMLog"
                               "-Dcom.mchange.v2.log.FallbackMLog.DEFAULT_CUTOFF_LEVEL=OFF"]}})
