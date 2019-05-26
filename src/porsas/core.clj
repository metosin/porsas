(ns porsas.core
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str])
  (:import (java.sql Connection PreparedStatement ResultSet ResultSetMetaData)
           (java.lang.reflect Field)
           (javax.sql DataSource)
           (clojure.lang PersistentVector)
           (java.util Iterator Map)))

(declare unqualified-key)

;;
;; Protocols
;;

(defprotocol RowCompiler
  (compile-row [this cols]))

(defprotocol GetConnection
  (get-connection [this]))

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

(defprotocol DataMapper
  (cache [this])
  (query-one [this ^Connection connection sqlvec])
  (query [this ^Connection connection sqlvec]))

;;
;; Implementation
;;

(defn- constructor-symbol [^Class record]
  (let [parts (str/split (.getName record) #"\.")]
    (-> (str (str/join "." (butlast parts)) "/->" (last parts))
        (str/replace #"_" "-")
        (symbol))))

(defn- record-fields [cls]
  (for [^Field f (.getFields ^Class cls)
        :when (and (= Object (.getType f))
                   (not (.startsWith (.getName f) "__")))]
    (.getName f)))

(def ^:private memoized-compile-record
  (memoize
    (fn [keys]
      (if-not (some qualified-keyword? keys)
        (let [sym (gensym "DBResult")
              mctor (symbol (str "map->" sym))
              pctor (symbol (str "->" sym))]
          (binding [*ns* (find-ns 'user)]
            (eval
              `(do
                 (defrecord ~sym ~(mapv (comp symbol name) keys))
                 {:name ~sym
                  :fields ~(mapv identity keys)
                  :instance (~mctor {})
                  :->instance ~pctor
                  :map->instance ~mctor}))))))))

(defn- rs->map-of-cols [cols]
  (let [size (* 2 (count cols))]
    (fn [^ResultSet rs]
      (let [a ^objects (make-array Object size)
            iter (clojure.lang.RT/iter cols)]
        (while (.hasNext iter)
          (let [v (.next iter)
                i ^int (nth v 0)
                i' (* 2 (dec i))]
            (aset a i' (nth v 1))
            (aset a (inc i') (.getObject rs ^Integer i))))
        (clojure.lang.PersistentArrayMap. a)))))

(defn- rs-> [pc fields]
  (let [rs (gensym)
        fm (zipmap fields (range 1 (inc (count fields))))]
    (eval
      `(fn [~(with-meta rs {:tag 'java.sql.ResultSet})]
         ~(if pc
            `(~pc ~@(map (fn [[k v]] `(.getObject ~rs ~v)) fm))
            (apply array-map (mapcat (fn [[k v]] [k `(.getObject ~rs ~v)]) fm)))))))

(defn- get-column-names [^ResultSet rs key]
  (let [rsmeta (.getMetaData rs)
        idxs (range 1 (inc (.getColumnCount rsmeta)))]
    (mapv (fn [^Integer i] (key rsmeta i)) idxs)))

(defn- col-map [^ResultSet rs key]
  (loop [i 1, acc [], [n & ns] (get-column-names rs key)]
    (if n (recur (inc i) (conj acc [i n]) ns) acc)))

(defn- prepare! [^PreparedStatement ps ^Iterator it]
  (when it
    (loop [i 1]
      (when (.hasNext it)
        (.setObject ps i (.next it))
        (recur (inc i))))))

;;
;; key
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
;; row
;;

(defn rs->record [record]
  (rs-> (constructor-symbol record) (record-fields record)))

(defn rs->compiled-record
  ([]
   (rs->compiled-record nil))
  ([_]
   (reify
     RowCompiler
     (compile-row [_ cols]
       (let [{:keys [->instance fields]} (memoized-compile-record cols)]
         (rs-> ->instance fields))))))

(defn rs->map
  ([]
   (rs->map nil))
  ([_]
   (reify
     RowCompiler
     (compile-row [_ cols]
       (rs-> nil cols)))))

;;
;; Queries
;;

(defn ^DataMapper compile
  "Returns a [[DataMapper]] instance from options map:

  | key           | description |
  | --------------|-------------|
  | `:row`        | Optional function of `rs->value` or a [[RowCompiler]] to convert rows into values
  | `:key`        | Optional function of `rs-meta i->key` to create key for map-results
  | `:cache`      | Optional [[java.util.Map]] instance to hold the compiled rowmappers"
  [{:keys [row key cache] :or {key (unqualified-key)
                               cache (java.util.HashMap.)}}]
  (let [cache (or cache (reify Map (get [_ _]) (put [_ _ _]) (entrySet [_])))
        ->row (fn [sql rs]
                (let [cols (col-map rs key)
                      row (cond
                            (satisfies? RowCompiler row) (compile-row row (map second cols))
                            row row
                            :else (rs->map-of-cols cols))]
                  (.put ^Map cache sql row)
                  row))]
    (reify
      DataMapper
      (cache [_] (into {} cache))
      (query-one [_ connection sqlvec]
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
      (query [_ connection sqlvec]
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
              (.close ps))))))))

(def default-mapper (compile nil))
