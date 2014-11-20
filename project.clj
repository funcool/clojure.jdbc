(defproject clojure.jdbc "0.3.2"
  :description "clojure.jdbc is a library for low level, jdbc-based database access."
  :url "http://github.com/niwibe/clojure.jdbc"
  :license {:name "Apache 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.txt"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [potemkin "0.3.4" :exclusions [org.clojure/clojure]]]
  :plugins [[lein-marginalia "0.7.1"]
            [codox "0.8.10"]]
  :javac-options ["-target" "1.7" "-source" "1.7" "-Xlint:-options"]
  :codox {:output-dir "doc/api"
          :src-dir-uri "http://github.com/niwibe/clojure.jdbc/blob/master/"
          :src-linenum-anchor-prefix "L"}
  :profiles {:bench {:source-paths ["bench/"]
                     :main jdbc.bench
                     :dependencies [[org.clojure/java.jdbc "0.3.5"]
                                    [postgresql "9.3-1102.jdbc41"]]}
             :test {:dependencies [[com.h2database/h2 "1.3.176"]
                                   [postgresql "9.3-1102.jdbc41"]
                                   [cheshire "5.3.1"]]}})
