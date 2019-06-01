(ns porsas.async
  (:require [porsas.core :as p])
  (:import (io.reactiverse.pgclient PgClient PgPoolOptions Tuple PgPool PgRowSet)
           (io.reactiverse.pgclient.impl ArrayTuple RowImpl)
           (io.vertx.core Handler AsyncResult)
           (java.util Collection HashMap Map)
           (clojure.lang PersistentVector)))

(defprotocol SQLParams
  (-get-sql [this])
  (-get-parameters [this]))

(extend-protocol SQLParams
  String
  (-get-sql [this] this)
  (-get-parameters [_] (Tuple/tuple))

  PersistentVector
  (-get-sql [this] (nth this 0))
  (-get-parameters [this]
    (ArrayTuple. ^Collection (subvec this 1))))

(extend-protocol p/GetValue
  RowImpl
  (get-value [rs i]
    (.getValue rs ^Integer i)))

(defprotocol DataMapper
  (cache [this])
  (query-one [this ^PgPool pool sqlvec f])
  (query [this ^PgPool pool sqlvec f]))

(defn- col-map [^PgRowSet rs]
  (loop [i 0, acc [], [n & ns] (mapv keyword (.columnsNames rs))]
    (if n (recur (inc i) (conj acc [i i n]) ns) acc)))

;;
;; pool
;;

(defn ^PgPool pool [{:keys [uri database host port user password size]}]
  (PgClient/pool
    ^PgPoolOptions
    (cond-> (if uri (PgPoolOptions/fromUri uri) (PgPoolOptions.))
            database (.setDatabase ^String database)
            host (.setHost ^String host)
            port (.setPort ^Integer port)
            user (.setUser ^String user)
            password (.setPassword ^String password)
            true (.setCachePreparedStatements true)
            size (.setMaxSize ^Integer size))))

;;
;; row
;;

(defn rs->record
  ([record]
   (rs->record record nil))
  ([record opts]
   (p/rs->record record (assoc opts :start 0))))

(defn rs->compiled-record
  ([]
   (rs->compiled-record nil))
  ([opts]
   (p/rs->compiled-record (assoc opts :start 0))))

(defn rs->map
  ([]
   (rs->map nil))
  ([opts]
   (p/rs->map (assoc opts :start 0))))

;;
;; DataMapper
;;

(defn ^DataMapper data-mapper
  "Returns a [[DataMapper]] instance from options map:

  | key           | description |
  | --------------|-------------|
  | `:row`        | Optional function of `tuple->value` or a [[RowCompiler]] to convert rows into values
  | `:cache`      | Optional [[java.util.Map]] instance to hold the compiled rowmappers"
  ([] (data-mapper {}))
  ([{:keys [row cache] :or {cache (HashMap.)}}]
   (let [cache (or cache (reify Map (get [_ _]) (put [_ _ _]) (entrySet [_])))
         ->row (fn [sql ^PgRowSet rs]
                 (let [cols (col-map rs)
                       row (cond
                             (satisfies? p/RowCompiler row) (p/compile-row row (map last cols))
                             row row
                             :else (p/rs->map-of-cols cols))]
                   (.put ^Map cache sql row)
                   row))]
     (reify
       DataMapper
       (cache [_] (into {} cache))
       (query-one [_ pool sqlvec f]
         (let [sql (-get-sql sqlvec)
               params (-get-parameters sqlvec)]
           (.preparedQuery
             ^PgPool pool
             ^String sql
             ^Tuple params
             (reify
               Handler
               (handle [_ res]
                 (if (.succeeded ^AsyncResult res)
                   (let [rs ^PgRowSet (.result ^AsyncResult res)
                         it (.iterator rs)]
                     (if-not (.hasNext it)
                       (f nil)
                       (let [row (or (.get ^Map cache sql) (->row sql rs))]
                         (f (row (.next it))))))))))))))))

;;
;; spike
;;

(comment

(ns async
  (:require [porsas.async :as pa]))

(def pool (pa/pool
            {:database "hello_world"
             :host "localhost"
             :port 5432
             :user "benchmarkdbuser"
             :password "benchmarkdbpass"
             :size 1}))

(def mapper (pa/data-mapper))

(pa/query-one mapper pool ["SELECT id, randomnumber from WORLD where id=$1" 1] println)
; => {:id 1, :randomnumber 6233}
)

(comment
  (defn queryz [[sql & params] f]
    (.preparedQuery
      pool
      ^String sql
      (ArrayTuple. ^Collection params)
      (reify
        Handler
        (handle [_ res]
          (if (.succeeded ^AsyncResult res)
            (let [rs ^PgRowSet (.result ^AsyncResult res)
                  it (.iterator rs)]
              #_(prn (into [] (.columnsNames rs)))
              (if-not (.hasNext it)
                (f nil)
                (f (.next it)))))))))

  (queryz
    ["SELECT id, randomnumber from WORLD where id=$1" 1]
    (fn [row]
      (println
        {:id (.getValue ^Tuple row 0)
         :randomnumber (.getValue ^Tuple row 1)}))))
