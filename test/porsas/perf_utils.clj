(ns porsas.perf-utils
  (:require [clojure.string :as str]
            [criterium.core :as cc]))

(defn raw-title [color s]
  (let [line-length (transduce (map count) max 0 (str/split-lines s))
        banner (apply str (repeat line-length "#"))]
    (println (str color banner "\u001B[0m"))
    (println (str color s "\u001B[0m"))
    (println (str color banner "\u001B[0m"))))

(def title (partial raw-title "\u001B[35m"))
(def suite (partial raw-title "\u001B[32m"))

(defmacro bench! [name & body]
  `(do
     (title ~name)
     (println "\u001B[33m")
     (clojure.pprint/pprint ~@body)
     (println "\u001B[0m")
     (cc/quick-bench ~@body)
     (println)))
