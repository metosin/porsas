(ns porsas.core-test
  (:require [porsas.core :as p]
            [porsas.perf-utils :refer :all]
            [clojure.java.jdbc :as j]
            [clj-async-profiler.core :as prof])
  (:import (java.sql ResultSet)))

(def db {:dbtype "h2:mem" :dbname "perf"})
(def con (j/get-connection db))

(try (j/execute! db ["DROP TABLE fruit"]) (catch Exception _))
(j/execute! db ["CREATE TABLE fruit (id int default 0, name varchar(32) primary key, appearance varchar(32), cost int, grade real)"])
(j/insert-multi! db :fruit [:id :name :appearance :cost :grade]
                 [[1 "Apple" "red" 59 87]
                  [2, "Banana", "yellow", 29, 92.2]
                  [3, "Peach", "fuzzy", 139, 90.0]
                  [4, "Orange", "juicy", 89, 88.6]])

(comment
  (prof/serve-files 8080))

(defn java-query [^String sql ^java.sql.Connection con]
  (let [ps (.prepareStatement con sql)
        rs (.executeQuery ps)
        res (loop [res []]
              (if (.next rs)
                (recur (conj res {:id (.getObject rs 1)
                                  :name (.getObject rs 2)
                                  :appearance (.getObject rs 3)
                                  :cost (.getObject rs 4)
                                  :grade (.getObject rs 5)}))
                res))]
    (.close ps)
    res))

;;
;; Fruits
;;

(defrecord Fruit [id name appearance cost grade])

(defn rs->fruit [^ResultSet rs]
  (->Fruit
    (.getObject rs 1)
    (.getObject rs 2)
    (.getObject rs 3)
    (.getObject rs 4)
    (.getObject rs 5)))

(defn bench-all! []

  ;; 730ns
  (bench!
    "java"
    (java-query "SELECT * FROM fruit" con))

  ;; 690ns (generate rs->record from a given record)
  (let [query (p/compile "SELECT * FROM fruit" {:row rs->fruit})]
    (bench!
      "porsas: manual, record"
      (query con)))

  ;; 710ns (generate rs->record from a given record)
  (let [query (p/compile "SELECT * FROM fruit" {:row (p/rs->record Fruit)})]
    (bench!
      "porsas: derived, record"
      (query con)))

  ;; 703ns (generate record and rs->record for this spesific query, use with care!)
  (let [query (p/compile "SELECT * FROM fruit" {:con con :row (p/rs->compiled-record)})]
    (bench!
      "porsas: compiled record"
      (query con)))

  ;; 2300ns (precompile the result maps)
  (let [query (p/compile "SELECT * FROM fruit" {:con con, :row (p/rs->map)})]
    (bench!
      "porsas: compiled map"
      (query con)))

  ;; 2600ns (interpret the result maps)
  (let [query (p/compile "SELECT * FROM fruit" {:con con})]
    (bench!
      "porsas: compiled map"
      (query con)))

  ;; 3500ns
  (let [query (p/compile "SELECT * FROM fruit")]
    (bench!
      "porsas: map"
      (query con)))

  ;; 8000ns
  (bench!
    "java.jdbc"
    (j/query {:connection con} ["SELECT * FROM fruit"])))

(comment
  (bench-all!))
