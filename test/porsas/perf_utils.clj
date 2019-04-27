(ns porsas.perf-utils
  (:require [clojure.string :as str]
            [criterium.core :as cc]))

(defn raw-title [color s]
  (let [line-length (+ 4 (transduce (map count) max 0 (str/split-lines s)))
        banner (apply str (repeat line-length "#"))]
    (println (str color banner "\u001B[0m"))
    (println (str color "# " s " #" "\u001B[0m"))
    (println (str color banner "\u001B[0m"))))

(def title (partial raw-title "\u001B[35m"))
(def suite (partial raw-title "\u001B[32m"))

(def ^:dynamic *show-response* false)
(def ^:dynamic *show-results* false)

(defn get-lower-q-ns [results] (int (+ 0.5 (* (first (:lower-q results)) 1e9))))

(defmacro bench! [body]
  `(do
     (when *show-response*
       (println "\u001B[33m")
       (clojure.pprint/pprint ~body)
       (print "\u001B[0m"))
     (let [results# (cc/quick-benchmark ~body nil)]
       (println)
       (println "\u001B[34m" (get-lower-q-ns results#) "ns" "\u001B[0m")
       (println)
       (when *show-results*
         (cc/report-result results#)
         (println)))))
