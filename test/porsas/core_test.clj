(ns porsas.core-test
  (:require [porsas.core :as p]
            [porsas.perf-utils :refer :all]
            [clojure.java.jdbc :as j]
            [clojure.string :as str])
  (:import (java.sql ResultSet Connection)))

(def db {:dbtype "h2:mem" :dbname "perf"})
(def ^Connection con (p/into-connection (j/get-connection db)))

(try (j/execute! db ["DROP TABLE fruit"]) (catch Exception _))
(j/execute! db ["CREATE TABLE fruit (id int default 0, name varchar(32) primary key, appearance varchar(32), cost int, grade real)"])
(j/insert-multi! db :fruit [:id :name :appearance :cost :grade]
                 [[1 "Apple" "red" 59 87]
                  [2, "Banana", "yellow", 29, 92.2]
                  [3, "Peach", "fuzzy", 139, 90.0]
                  [4, "Orange", "juicy", 89, 88.6]])

(defrecord Fruit [id name appearance cost grade])

(defn rs->Fruit [^ResultSet rs]
  (->Fruit
    (.getObject rs 1)
    (.getObject rs 2)
    (.getObject rs 3)
    (.getObject rs 4)
    (.getObject rs 5)))

(defn java-query [^String sql ^java.sql.Connection con]
  (let [ps (.prepareStatement con sql)
        rs (.executeQuery ps)
        res (loop [res []]
              (if (.next rs)
                (recur (conj res (rs->Fruit rs)))
                res))]
    (.close ps)
    res))

(comment

  ;; 760ns
  (let [query (partial java-query "SELECT * FROM fruit")]
    (title "java")
    (bench! (query con)))

  ;; 760ns
  (let [query (p/compile
                "SELECT * FROM fruit"
                {:row rs->Fruit})]
    (bench! "porsas: manual, record" (query con)))

  ;; 720ns
  (let [query (p/compile
                "SELECT * FROM fruit"
                {:row (p/rs->record Fruit)})]
    (title "porsas: derived, record")
    (bench! (query con)))

  ;; 720ns
  (let [query (p/compile
                "SELECT * FROM fruit"
                {:con con
                 :row (p/rs->compiled-record)
                 :key (p/unqualified-key str/lower-case)})]
    (title "porsas: generated record")
    (bench! (query con)))

  ;; 780ns
  (let [query (p/compile
                "SELECT * FROM fruit"
                {:con con
                 :row (p/rs->map)
                 :key (p/unqualified-key str/lower-case)})]
    (title "porsas: compiled map, unqualified")
    (bench! (query con)))

  ;; 780ns
  (let [query (p/compile
                "SELECT * FROM fruit"
                {:con con
                 :row (p/rs->map)
                 :key (p/qualified-key str/lower-case)})]
    (title "porsas: compiled map, qualified")
    (bench! (query con)))

  ;; 1000ns
  (let [query (p/compile-batch
                "SELECT * FROM fruit"
                {:con con
                 :row (p/rs->map)
                 :key (p/qualified-key str/lower-case)})]
    (title "porsas: compiled map, qualified, batch")
    (bench! (query con (constantly nil))))

  ;; 2300ns
  (let [query (p/compile
                "SELECT * FROM fruit"
                {:con con
                 :key (p/unqualified-key str/lower-case)})]
    (title "porsas: interpreted map, unqualified")
    (bench! (query con)))

  ;; 2300ns
  (let [query (p/compile
                "SELECT * FROM fruit"
                {:con con
                 :key (p/qualified-key str/lower-case)})]
    (title "porsas: interpreted map, qualified")
    (bench! (query con)))

  ;; 3200ns
  (let [query (p/compile
                "SELECT * FROM fruit"
                {:key (p/unqualified-key str/lower-case)})]
    (title "porsas: dynamic map, unqualified")
    (bench! (query con)))

  ;; 3200ns
  (let [query (p/compile
                "SELECT * FROM fruit"
                {:key (p/qualified-key str/lower-case)})]
    (title "porsas: dynamic map, unqualified")
    (bench! (query con)))

  ;; 9200ns
  (let [query #(j/query {:connection %} ["SELECT * FROM fruit"])]
    (title "java.jdbc")
    (bench! (query con))))

(comment
  (let [meta (.getMetaData (.executeQuery (.prepareStatement con "select * from fruit")))]
    (doseq [i (range 1 (inc (.getColumnCount meta)))]
      (println "   index:" i)
      (println "    name:" (.getColumnName meta i))
      (println "   label:" (.getColumnLabel meta i))
      (println "  schema:" (.getSchemaName meta i))
      (println "   table:" (.getTableName meta i))
      (println " catalog:" (.getCatalogName meta i))
      (println "    type:" (.getColumnTypeName meta i))
      (println "   class:" (.getColumnClassName meta i))
      (println)
      (println "     key:" ((p/unqualified-key str/lower-case) meta i))
      (println "    qkey:" ((p/qualified-key str/lower-case) meta i))
      (println))))
