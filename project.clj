(defproject funcool/clojure.jdbc "0.5.1"
  :description "clojure.jdbc is a library for low level, jdbc-based database access."
  :url "http://github.com/niwibe/clojure.jdbc"
  :license {:name "Apache 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.txt"}
  :dependencies [[org.clojure/clojure "1.6.0" :scope "provided"]
                 [potemkin "0.3.13"]]
  :javac-options ["-target" "1.7" "-source" "1.7" "-Xlint:-options"]
  :profiles {:dev {:dependencies [[com.h2database/h2 "1.4.187"]
                                  [postgresql "9.3-1102.jdbc41"]
                                  [hikari-cp "1.2.2" :exclusions [com.zaxxer/HikariCP]]
                                  [com.zaxxer/HikariCP-java6 "2.3.7"]
                                  [cheshire "5.4.0"]]
                   :codeina {:sources ["src"]
                             :exclude [jdbc.core-deprecated
                                       jdbc.impl
                                       jdbc.transaction
                                       jdbc.types]
                             :language :clojure
                             :output-dir "doc/api"
                             :src-dir-uri "http://github.com/niwibe/clojure.jdbc/blob/master/"
                             :src-linenum-anchor-prefix "L"}
                   :plugins [[lein-marginalia "0.7.1"]
                             [funcool/codeina "0.1.0-SNAPSHOT"
                              :exclusions [org.clojure/clojure]]]}
             :bench {:source-paths ["bench/"]
                     :main jdbc.bench
                     :global-vars {*warn-on-reflection* true
                                   *unchecked-math* :warn-on-boxed}
                     :dependencies [[org.clojure/clojure "1.7.0-beta2"]
                                    [org.clojure/java.jdbc "0.3.6"]
                                    [com.h2database/h2 "1.4.187"]
                                    [criterium "0.4.3"]]}})
