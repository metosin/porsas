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
    (bench! "java" (query con)))

  ;; 760ns
  (let [query (p/compile "SELECT * FROM fruit" {:row rs->Fruit})]
    (bench! "porsas: manual, record" (query con)))

  ;; 720ns
  (let [query (p/compile "SELECT * FROM fruit" {:row (p/rs->record Fruit)})]
    (bench! "porsas: derived, record" (query con)))

  ;; 720ns
  (let [query (p/compile "SELECT * FROM fruit" {:con con
                                                :row (p/rs->compiled-record)
                                                :key (p/unqualified-key str/lower-case)})]
    (bench! "porsas: generated record" (query con)))

  ;; 780ns
  (let [query (p/compile "SELECT * FROM fruit" {:con con
                                                :row (p/rs->map)
                                                :key (p/unqualified-key str/lower-case)})]
    (bench! "porsas: compiled map, unqualified" (query con)))

  ;; 780ns
  (let [query (p/compile "SELECT * FROM fruit" {:con con
                                                :row (p/rs->map)
                                                :key (p/qualified-key str/lower-case)})]
    (bench! "porsas: compiled map, qualified" (query con)))

  ;; 2300ns
  (let [query (p/compile "SELECT * FROM fruit" {:con con
                                                :key (p/unqualified-key str/lower-case)})]
    (bench! "porsas: interpreted map, unqualified" (query con)))

  ;; 2300ns
  (let [query (p/compile "SELECT * FROM fruit" {:con con
                                                :key (p/qualified-key str/lower-case)})]
    (bench! "porsas: interpreted map, qualified" (query con)))

  ;; 3200ns
  (let [query (p/compile "SELECT * FROM fruit" {:key (p/unqualified-key str/lower-case)})]
    (bench! "porsas: dynamic map, unqualified" (query con)))

  ;; 3200ns
  (let [query (p/compile "SELECT * FROM fruit" {:key (p/qualified-key str/lower-case)})]
    (bench! "porsas: dynamic map, unqualified" (query con)))

  ;; 9200ns
  (let [query #(j/query {:connection %} ["SELECT * FROM fruit"])]
    (bench! "java.jdbc" (query con))))

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

(ns user)

(require '[porsas.core :as p])
(require '[clojure.java.jdbc :as j])
(require '[clojure.string :as str])

(def con
  (clojure.java.jdbc/get-connection
           {:dbtype "h2:mem" :dbname "perf"}))

(try (j/execute! db ["DROP TABLE fruit"]) (catch Exception _))
(j/execute! db ["CREATE TABLE fruit (id int default 0, name varchar(32) primary key, appearance varchar(32), cost int, grade real)"])
(j/insert-multi! db :fruit [:id :name :appearance :cost :grade]
                 [[1 "Apple" "red" 59 87]
                  [2, "Banana", "yellow", 29, 92.2]
                  [3, "Peach", "fuzzy", 139, 90.0]
                  [4, "Orange", "juicy", 89, 88.6]])


(defrecord Fruit [id name appearance cost grade])

(def get-fruits
  (p/compile "SELECT * FROM fruit" {:row (p/rs->record Fruit)}))

(get-fruits con)
;[#user.Fruit{:id 1, :name "Apple", :appearance "red", :cost 59, :grade 87.0}
; #user.Fruit{:id 2, :name "Banana", :appearance "yellow", :cost 29, :grade 92.2}
; #user.Fruit{:id 3, :name "Peach", :appearance "fuzzy", :cost 139, :grade 90.0}
; #user.Fruit{:id 4, :name "Orange", :appearance "juicy", :cost 89, :grade 88.6}]


(def select-id-name-from-fruit
  (p/compile
    "SELECT id, name FROM fruit"
    {:con con
     :row (p/rs->compiled-record)
     :key (p/unqualified-key str/lower-case)}))

(select-id-name-from-fruit con)
;[#user.DBEntry14487{:id 1, :name "Apple"}
; #user.DBEntry14487{:id 2, :name "Banana"}
; #user.DBEntry14487{:id 3, :name "Peach"}
; #user.DBEntry14487{:id 4, :name "Orange"}]

(def get-fruits-map
  (p/compile
    "SELECT * FROM fruit"
    {:con con
     :row (p/rs->map)
     :key (p/unqualified-key str/lower-case)}))

(get-fruits-map con)
;[{:id 1, :name "Apple", :appearance "red", :cost 59, :grade 87.0}
; {:id 2, :name "Banana", :appearance "yellow", :cost 29, :grade 92.2}
; {:id 3, :name "Peach", :appearance "fuzzy", :cost 139, :grade 90.0}
; {:id 4, :name "Orange", :appearance "juicy", :cost 89, :grade 88.6}]


(def get-fruits-map-qualified
  (p/compile
    "SELECT * FROM fruit"
    {:con con
     :row (p/rs->map)
     :key (p/qualified-key str/lower-case)}))

(get-fruits-map-qualified con)
;[#:fruit{:id 1, :name "Apple", :appearance "red", :cost 59, :grade 87.0}
; #:fruit{:id 2, :name "Banana", :appearance "yellow", :cost 29, :grade 92.2}
; #:fruit{:id 3, :name "Peach", :appearance "fuzzy", :cost 139, :grade 90.0}
; #:fruit{:id 4, :name "Orange", :appearance "juicy", :cost 89, :grade 88.6}]

(def dynamic-get-fruits-map-qulified
  (partial (p/compile "SELECT name, cost FROM fruit" {:key (p/qualified-key str/lower-case)})))

(dynamic-get-fruits-map-qulified con)
;[#:fruit{:name "Apple", :cost 59}
; #:fruit{:name "Banana", :cost 29}
; #:fruit{:name "Peach", :cost 139}
; #:fruit{:name "Orange", :cost 89}]

(def get-fruits-by-color
  (p/compile
    "SELECT * FROM fruit where appearance = ?"
    {:con con
     :row (p/rs->map)
     :key (p/qualified-key str/lower-case)}))

(get-fruits-by-color con ["red"])
;[#:fruit{:id 1, :name "Apple", :appearance "red", :cost 59, :grade 87.0}]


(comment

  ;; 3200ns
  (let [query (p/compile "SELECT * FROM fruit" {:key (p/unqualified-key str/lower-case)})]
    (bench! "porsas: dynamic map, unqualified" (query con)))

  ;; 3200ns
  (let [query (p/compile "SELECT * FROM fruit" {:key (p/qualified-key str/lower-case)})]
    (bench! "porsas: dynamic map, unqualified" (query con)))

  ;; 9200ns
  (let [query #(j/query {:connection %} ["SELECT * FROM fruit"])]
    (bench! "java.jdbc" (query con))))
