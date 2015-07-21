(defproject funcool/clojure.jdbc "0.5.1"
  :description "clojure.jdbc is a library for low level, jdbc-based database access."
  :url "http://github.com/niwibe/clojure.jdbc"
  :license {:name "Apache 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.txt"}
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [potemkin "0.4.1"]]
  :javac-options ["-target" "1.7" "-source" "1.7" "-Xlint:-options"]
  :profiles {:dev {:dependencies [[com.h2database/h2 "1.4.187"]
                                  [postgresql "9.3-1102.jdbc41"]
                                  [hikari-cp "1.2.4" :exclusions [com.zaxxer/HikariCP]]
                                  [com.zaxxer/HikariCP-java6 "2.3.9"]
                                  [cheshire "5.5.0"]]
                   :codeina {:sources ["src"]
                             :exclude [jdbc.core-deprecated
                                       jdbc.impl
                                       jdbc.transaction
                                       jdbc.types]
                             :reader :clojure
                             :target "doc/dist/latest/api"
                             :src-uri "http://github.com/niwibe/clojure.jdbc/blob/master/"
                             :src-uri-prefix "#L"}
                   :plugins [[lein-marginalia "0.7.1"]
                             [lein-ancient "0.6.7"]
                             [funcool/codeina "0.2.0"]]}
             :bench {:source-paths ["bench/"]
                     :main jdbc.bench
                     :global-vars {*warn-on-reflection* true
                                   *unchecked-math* :warn-on-boxed}
                     :dependencies [[org.clojure/clojure "1.7.0"]
                                    [org.clojure/java.jdbc "0.3.9"]
                                    [com.h2database/h2 "1.4.187"]
                                    [criterium "0.4.3"]]}})
