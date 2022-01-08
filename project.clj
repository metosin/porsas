(defproject metosin/porsas "0.0.1-alpha13"
  :description "Spike Lee for Fast DB-access with Clojure"
  :url "https://github.com/metosin/porsas"
  :deploy-repositories [["releases" :clojars]]
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :scm {:name "git"
        :url "https://github.com/metosin/porsas"}
  :dependencies [[org.postgresql/postgresql "42.3.1"]
                 [io.vertx/vertx-pg-client  "4.2.1"]]
  :profiles {:dev {:global-vars {*warn-on-reflection* true}
                   :dependencies [[org.clojure/clojure "1.10.3"]
                                  [com.clojure-goes-fast/clj-async-profiler "0.5.1"]
                                  [org.clojure/core.cache "1.0.225"]
                                  [com.github.ben-manes.caffeine/caffeine "3.0.5"]
                                  [funcool/promesa "2.0.1"]
                                  [manifold "0.1.8"]
                                  [com.h2database/h2 "1.4.199"]
                                  [seancorfield/next.jdbc "1.2.659"]
                                  [funcool/clojure.jdbc "0.9.0"]
                                  [org.clojure/java.jdbc "0.7.9"]
                                  [criterium "0.4.6"]]}
             :perf {:jvm-opts ^:replace ["-server"
                                         "-Xmx4096m"
                                         "-Dclojure.compiler.direct-linking=true"]}}
  :aliases {"all" ["with-profile" "dev"]
            "perf" ["with-profile" "default,dev,perf"]
            "test-clj" ["all" "do" ["test"] ["check"]]})
