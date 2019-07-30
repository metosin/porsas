(ns porsas.jdbc
  (:require [porsas.core :as p])
  (:import (java.sql Connection PreparedStatement ResultSet ResultSetMetaData)
           (javax.sql DataSource)
           (java.util Iterator Map)
           (clojure.lang PersistentVector)))

;;
;; Protocols
;;

(defprotocol GetConnection
  (^java.sql.Connection get-connection [this]))

(extend-protocol GetConnection
  Connection
  (get-connection [this] this)

  DataSource
  (get-connection [this] (.getConnection this)))

(defprotocol SQLParams
  (-get-sql [this])
  (-get-parameter-iterator [this]))

(extend-protocol SQLParams
  String
  (-get-sql [this] this)
  (-get-parameter-iterator [_])

  PersistentVector
  (-get-sql [this] (nth this 0))
  (-get-parameter-iterator [this]
    (let [it (.iterator this)]
      (when (.hasNext it)
        (.next it)
        it))))

(extend-protocol p/GetValue
  ResultSet
  (get-value [rs i]
    (.getObject rs ^Integer i)))

(defprotocol Context
  (-query-one [this ^Connection connection sqlvec])
  (-query [this ^Connection connection sqlvec]))

;;
;; Implementation
;;

(defn- get-column-names [^ResultSet rs key]
  (let [rsmeta (.getMetaData rs)
        idxs (range 1 (inc (.getColumnCount rsmeta)))]
    (mapv (fn [^Integer i] (key rsmeta i)) idxs)))

(defn ^:no-doc col-map [^ResultSet rs key]
  (loop [i 0, acc [], [n & ns] (get-column-names rs key)]
    (if n (recur (inc i) (conj acc [(inc i) i n]) ns) acc)))

(defn- prepare! [^PreparedStatement ps ^Iterator it]
  (when it
    (loop [i 1]
      (when (.hasNext it)
        (.setObject ps i (.next it))
        (recur (inc i))))))

;;
;; keys
;;

(defn unqualified-key
  ([]
   (unqualified-key identity))
  ([f]
   (fn unqualified-key [^ResultSetMetaData rsmeta, ^Integer i]
     (keyword (f (.getColumnLabel rsmeta i))))))

(defn qualified-key
  ([]
   (qualified-key identity identity))
  ([f]
   (qualified-key f f))
  ([ft fc]
   (fn qualified-key [^ResultSetMetaData rsmeta, ^Integer i]
     (keyword (ft (.getTableName rsmeta i)) (fc (.getColumnLabel rsmeta i))))))

;;
;; rows
;;

(defn rs->record
  ([record]
   (rs->record record nil))
  ([record opts]
   (p/rs->record record opts)))

(defn rs->compiled-record
  ([]
   (rs->compiled-record nil))
  ([opts]
   (p/rs->compiled-record opts)))

(defn rs->map
  ([]
   (rs->map nil))
  ([opts]
   (p/rs->map opts)))

;;
;; mapper
;;

(defn ^Context context
  "Returns a [[Context]] instance from options map:

  | key           | description |
  | --------------|-------------|
  | `:row`        | Optional function of `rs->value` or a [[RowCompiler]] to convert rows into values
  | `:key`        | Optional function of `rs-meta i->key` to create key for map-results
  | `:cache`      | Optional [[java.util.Map]] instance to hold the compiled rowmappers"
  ([] (context {}))
  ([{:keys [row key cache] :or {key (unqualified-key)
                                cache (java.util.HashMap.)}}]
   (let [cache (or cache (reify Map (get [_ _]) (put [_ _ _]) (entrySet [_])))
         ->row (fn [sql rs]
                 (let [cols (col-map rs key)
                       row (cond
                             (satisfies? p/RowCompiler row) (p/compile-row row (map last cols))
                             row row
                             :else (p/rs->map-of-cols cols))]
                   (.put ^Map cache sql row)
                   row))]
     (reify
       p/Cached
       (cache [_] (into {} cache))
       Context
       (-query-one [_ connection sqlvec]
         (let [sql (-get-sql sqlvec)
               params (-get-parameter-iterator sqlvec)
               ps (.prepareStatement ^Connection connection sql)]
           (try
             (prepare! ps params)
             (let [rs (.executeQuery ps)
                   row (or (.get ^Map cache sql) (->row sql rs))]
               (if (.next rs) (row rs)))
             (finally
               (.close ps)))))
       (-query [_ connection sqlvec]
         (let [sql (-get-sql sqlvec)
               it (-get-parameter-iterator sqlvec)
               ps (.prepareStatement ^Connection connection sql)]
           (try
             (prepare! ps it)
             (let [rs (.executeQuery ps)
                   row (or (.get ^Map cache sql) (->row sql rs))]
               (loop [res []]
                 (if (.next rs)
                   (recur (conj res (row rs)))
                   res)))
             (finally
               (.close ps)))))))))

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
