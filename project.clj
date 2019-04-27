(defproject metosin/porsas "0.0.1-alpha4"
  :description "Spike Lee for Fast DB-access"
  :url "https://github.com/metosin/porsas"
  :deploy-repositories [["releases" :clojars]]
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :scm {:name "git"
        :url "https://github.com/metosin/porsas"}
  :dependencies []
  :profiles {:dev {:global-vars {*warn-on-reflection* true}
                   :dependencies [[org.clojure/clojure "1.10.0"]
                                  [com.clojure-goes-fast/clj-async-profiler "0.3.0"]
                                  [com.h2database/h2 "1.4.197"]
                                  [seancorfield/next.jdbc "1.0.0-alpha11"]
                                  [funcool/clojure.jdbc "0.9.0"]
                                  [org.clojure/java.jdbc "0.7.9"]
                                  [criterium "0.4.4"]]}
             :perf {:jvm-opts ^:replace ["-server"
                                         "-Xmx4096m"
                                         "-Dclojure.compiler.direct-linking=true"]}}
  :aliases {"all" ["with-profile" "dev"]
            "perf" ["with-profile" "default,dev,perf"]
            "test-clj" ["all" "do" ["test"] ["check"]]})
