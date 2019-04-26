(ns porsas.core-test
  (:require [porsas.core :as p]
            [porsas.perf-utils :refer :all]
            [clojure.java.jdbc :as j]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
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

(defn caching-row-builder
  ([]
   (caching-row-builder (p/qualified-key)))
  ([key]
   (let [cache (HashMap.)] ;; TODO: make bounded
     (fn [^ResultSet rs opts]
       (let [sql (:next.jdbc/sql-string opts)
             ->row (or (.get cache sql)
                       (let [->row (#'p/rs-> nil (map second (#'p/col-map rs key)))]
                         (.put cache sql ->row)
                         ->row))]
         (reify
           rs/RowBuilder
           (->row [_] (->row rs))
           (with-column [_ row _] row)
           (column-count [_] 0)
           (row! [_ row] row)
           rs/ResultSetBuilder
           (->rs [_] (transient []))
           (with-row [_ rs row] (conj! rs row))
           (rs! [_ rs] (persistent! rs))))))))

(defn perf-test []

  ;; 630ns
  (title "java")
  (bench! (java-query connection "SELECT * FROM fruit"))

  ;; 630ns
  (let [query (p/create-query {:row (p/rs->map)})]
    (title "porsas: compiled query")
    (bench! (query connection "SELECT * FROM fruit")))

  ;; 630ns
  (let [query (p/create-query {:row rs->Fruit})]
    (title "porsas: hand-written")
    (bench! (query connection "SELECT * FROM fruit")))

  ;; 630ns
  (let [query (p/create-query {:row (p/rs->record Fruit)})]
    (title "porsas: inferred from record")
    (bench! (query connection "SELECT * FROM fruit")))

  ;; 630ns
  (let [query (p/create-query {:row (p/rs->compiled-record)})]
    (title "porsas: compiled record")
    (bench! (query connection "SELECT * FROM fruit")))

  ;; 1300ns
  (let [query (p/create-query)]
    (title "porsas: cached query")
    (bench! (query connection "SELECT * FROM fruit")))

  ;; 1500ns
  (title "next.jdbc: compiled")
  (let [cached-builder (caching-row-builder)]
    (bench! (jdbc/execute! connection ["SELECT * FROM fruit"] {:builder-fn cached-builder})))

  ;; 2100ns
  (title "porsas: dynamic query")
  (bench! (p/query connection "SELECT * FROM fruit"))

  ;; 4000ns
  (title "next.jdbc")
  (bench! (jdbc/execute! connection ["SELECT * FROM fruit"]))

  ;; 4700µs
  (title "clojure.jdbc")
  (bench! (funcool/fetch connection ["SELECT * FROM fruit"]))

  ;; 6500ns
  (title "java.jdbc")
  (bench! (j/query {:connection connection} ["SELECT * FROM fruit"])))

(comment
  (perf-test))

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
