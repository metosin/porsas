(ns porsas.async
  (:require [porsas.core :as p])
  (:import (io.vertx.pgclient PgPool PgConnectOptions)
           (io.vertx.sqlclient PoolOptions Tuple RowSet)
           io.vertx.sqlclient.impl.ArrayTuple
           io.vertx.pgclient.impl.RowImpl
           (io.vertx.core Vertx Handler AsyncResult VertxOptions)
           (java.util Collection HashMap Map)
           (clojure.lang PersistentVector)
           (java.util.concurrent CompletableFuture Executor CompletionStage)
           (java.util.function Function)))

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

(defprotocol Context
  (^java.util.concurrent.CompletionStage -query-one [this ^PgPool pool sqlvec])
  (^java.util.concurrent.CompletionStage -query [this ^PgPool pool sqlvec]))

(defn- col-map [^RowSet rs]
  (loop [i 0, acc [], [n & ns] (mapv keyword (.columnsNames rs))]
    (if n (recur (inc i) (conj acc [i i n]) ns) acc)))

;;
;; pooling
;;

(defn ^Vertx vertx []
  (Vertx/vertx (.setPreferNativeTransport (VertxOptions.) true)))

(defn ^PgConnectOptions options [{:keys [uri database host port user password pipelining-limit size]}]
  (cond-> (if uri (PgConnectOptions/fromUri ^String uri) (PgConnectOptions.))
          database (.setDatabase ^String database)
          host (.setHost ^String host)
          port (.setPort ^Integer port)
          user (.setUser ^String user)
          password (.setPassword ^String password)
          true (.setCachePreparedStatements true)
          pipelining-limit (.setPipeliningLimit ^Integer pipelining-limit)
          size (.setMaxSize ^Integer size)))

(defn ^PoolOptions ->poolOptions [{:keys [max-size]}]
  (cond-> (PoolOptions.)
    max-size (.setMaxSize max-size)))

(defn ^PgPool pool
  ([options]
   (pool (vertx) options))
  ([vertx opts]
   (let [opts (if (instance? PgConnectOptions opts) opts (options opts))]
     (PgPool/pool ^Vertx vertx ^PgConnectOptions opts (->poolOptions {}))))) ;;TODO expose poolOptions to the interface

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

(defn ^Context context
  "Returns a [[Context]] instance from options map:

  | key           | description |
  | --------------|-------------|
  | `:row`        | Optional function of `tuple->value` or a [[RowCompiler]] to convert rows into values
  | `:cache`      | Optional [[java.util.Map]] instance to hold the compiled rowmappers"
  ([] (context {}))
  ([{:keys [row cache] :or {cache (HashMap.)}}]
   (let [cache (or cache (reify Map (get [_ _]) (put [_ _ _]) (entrySet [_])))
         ->row (fn [sql ^RowSet rs]
                 (let [cols (col-map rs)
                       row  (cond
                              (satisfies? p/RowCompiler row) (p/compile-row row (map last cols))
                              row                            row
                              :else                          (p/rs->map-of-cols cols))]
                   (.put ^Map cache sql row)
                   row))]
     (reify
       p/Cached
       (cache [_] (into {} cache))
       Context
       (-query-one [_ pool sqlvec]
         (let [sql    (-get-sql sqlvec)
               params (-get-parameters sqlvec)
               cf     (CompletableFuture.)]
           (-> (.preparedQuery ^PgPool pool ^String sql)
               (.execute ^Tuple params
                         (reify
                           Handler
                           (handle [_ res]
                             (if (.succeeded ^AsyncResult res)
                               (let [rs ^RowSet (.result ^AsyncResult res)
                                     it (.iterator rs)]
                                 (if-not (.hasNext it)
                                   (.complete cf nil)
                                   (let [row (or (.get ^Map cache sql) (->row sql rs))]
                                     (.complete cf (row (.next it))))))
                               (.completeExceptionally cf (.cause ^AsyncResult res)))))))
           cf))
       (-query [_ pool sqlvec]
         (let [sql    (-get-sql sqlvec)
               params (-get-parameters sqlvec)
               cf     (CompletableFuture.)]
           (-> (.preparedQuery ^PgPool pool ^String sql)
               (.execute ^Tuple params
                         (reify
                           Handler
                           (handle [_ res]
                             (if (.succeeded ^AsyncResult res)
                               (let [rs  ^RowSet (.result ^AsyncResult res)
                                     it  (.iterator rs)
                                     row (or (.get ^Map cache sql) (->row sql rs))]
                                 (loop [res []]
                                   (if (.hasNext it)
                                     (recur (conj res (row (.next it))))
                                     (.complete cf res))))
                               (.completeExceptionally cf (.cause ^AsyncResult res)))))))
           cf))))))

;;
;; public api
;;

(defn query-one
  ([connection sqlvec]
   (-query-one (context) connection sqlvec))
  ([context connection sqlvec]
   (-query-one context connection sqlvec)))

(defn query
  ([connection sqlvec]
   (-query (context) connection sqlvec))
  ([context connection sqlvec]
   (-query context connection sqlvec)))

;;
;; utils
;;

(defn then [^CompletionStage cf f]
  (.thenApply cf (reify Function
                   (apply [_ response]
                     (f response)))))

(defn then-async
  ([^CompletionStage cf f]
   (.thenApplyAsync cf (reify Function
                         (apply [_ response]
                           (f response)))))
  ([^CompletionStage cf f ^Executor executor]
   (.thenApplyAsync cf (reify Function
                         (apply [_ response]
                           (f response))) executor)))

(defn catch [^CompletionStage cf f]
  (.exceptionally cf (reify Function
                       (apply [_ exception]
                         (f exception)))))
