(ns porsas.core-test
  (:require [porsas.core :as p]
            [porsas.next :as pn]
            [porsas.perf-utils :refer :all]
            [clojure.java.jdbc :as j]
            [next.jdbc :as jdbc]
            [jdbc.core :as funcool]
            [clojure.string :as str]
            [criterium.core :as cc])
  (:import (java.sql ResultSet Connection)
           (java.util HashMap)))

(def db {:dbtype "h2:mem" :dbname "perf"})
(def ^Connection connection (j/get-connection db))

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

(defn java-query [^java.sql.Connection connection ^String sql]
  (let [ps (.prepareStatement connection sql)
        rs (.executeQuery ps)
        res (loop [res []]
              (if (.next rs)
                (recur (conj res (rs->Fruit rs)))
                res))]
    (.close ps)
    res))

(defn perf-test []

  ;; 770ns
  (title "java")
  (bench! (java-query connection "SELECT * FROM fruit"))

  ;; 840ns
  (let [mapper (p/data-mapper {:row (p/rs->map)})]
    (title "porsas: compiled query")
    (bench! (p/query mapper connection "SELECT * FROM fruit")))

  ;; 830ns
  (let [mapper (p/data-mapper {:row rs->Fruit})]
    (title "porsas: hand-written")
    (bench! (p/query mapper connection "SELECT * FROM fruit")))

  ;; 810ns
  (let [mapper (p/data-mapper {:row (p/rs->record Fruit)})]
    (title "porsas: inferred from record")
    (bench! (p/query mapper connection "SELECT * FROM fruit")))

  ;; 780ns
  (let [mapper (p/data-mapper {:row (p/rs->compiled-record)})]
    (title "porsas: compiled record")
    (bench! (p/query mapper connection "SELECT * FROM fruit")))

  ;; 1700ns
  (title "porsas: cached query")
  (bench! (p/query p/default-mapper connection "SELECT * FROM fruit"))

  ;; 2400ns
  (title "porsas: dynamic query")
  (let [mapper (p/data-mapper {:cache nil})]
    (bench! (p/query mapper connection "SELECT * FROM fruit")))

  ;; 1700ns
  (title "next.jdbc: porsas compiled")
  (let [cached-builder (pn/caching-row-builder)]
    (bench! (jdbc/execute! connection ["SELECT * FROM fruit"] {:builder-fn cached-builder})))

  ;; 4400ns
  (title "next.jdbc")
  (bench! (jdbc/execute! connection ["SELECT * FROM fruit"]))

  ;; 5000µs
  (title "clojure.jdbc")
  (bench! (funcool/fetch connection ["SELECT * FROM fruit"]))

  ;; 7000ns
  (title "java.jdbc")
  (bench! (j/query {:connection connection} ["SELECT * FROM fruit"])))

(defn perf-test-one []

  ;; 480ns
  (let [mapper (p/data-mapper {:row (p/rs->map)})]
    (title "porsas: compiled query")
    (bench! (p/query-one mapper connection ["SELECT * FROM fruit where appearance = ? " "red"])))

  ;; 1890ns
  (title "next.jdbc")
  (bench! (jdbc/execute-one! connection ["SELECT * FROM fruit where appearance = ? " "red"]))

  ;; 4300ns
  (title "java.jdbc")
  (bench! (j/query {:connection connection} ["SELECT * FROM fruit where appearance = ? " "red"] {:result-set-fn first})))

(comment

  (binding [*show-results* false, *show-response* false]
    (perf-test))

  (binding [*show-results* false, *show-response* true]
    (perf-test-one)))

(comment
  (let [meta (.getMetaData (.executeQuery (.prepareStatement connection "select * from fruit")))]
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

(comment
  (let [cols [[1 :ID] [2 :NAME] [3 :APPEARANCE] [4 :COST] [5 :GRADE]]
        size (* 2 (count cols))
        values [nil 1 "Apple" "red" 59 87.0]
        f1 (fn [values]
             (reduce
               (fn [acc [i k]]
                 (assoc acc k (nth values i)))
               nil
               cols))
        f2 (fn [values]
             (let [a ^objects (make-array Object size)
                   iter (clojure.lang.RT/iter cols)]
               (while (.hasNext iter)
                 (let [v (.next iter)
                       i ^int (nth v 0)
                       i' (* 2 (dec i))]
                   (aset a i' (nth v 1))
                   (aset a (inc i') (nth values i))))
               (clojure.lang.PersistentArrayMap. a)))]
    ;; 302ns
    (cc/quick-bench (f1 values))
    ;; 133ns
    (cc/quick-bench (f2 values))))
