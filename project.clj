(defproject funcool/clojure.jdbc "0.9.0"
  :description "clojure.jdbc is a library for low level, jdbc-based database access."
  :url "http://github.com/niwibe/clojure.jdbc"
  :license {:name "Apache 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.txt"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]]
  :javac-options ["-target" "1.7" "-source" "1.7" "-Xlint:-options"]
  :profiles
  {:dev
   {:dependencies [[com.h2database/h2 "1.4.191"]
                   [org.postgresql/postgresql "9.4.1208.jre7"]
                   [hikari-cp "1.6.1"]
                   [cheshire "5.6.1"]]
    :codeina {:sources ["src"]
              :exclude [jdbc.impl
                        jdbc.transaction
                        jdbc.types]
              :reader :clojure
              :target "doc/dist/latest/api"
              :src-uri "http://github.com/niwibe/clojure.jdbc/blob/master/"
              :src-uri-prefix "#L"}
    :plugins [[lein-ancient "0.6.10"]
              [funcool/codeina "0.4.0"]]}
   :bench {:source-paths ["bench/"]
           :main jdbc.bench
           :global-vars {*warn-on-reflection* true
                         *unchecked-math* :warn-on-boxed}
           :dependencies [[org.clojure/java.jdbc "0.5.8"]
                          [com.h2database/h2 "1.4.191"]
                          [criterium "0.4.4"]]}})
