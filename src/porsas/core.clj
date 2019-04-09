(ns porsas.core
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str])
  (:import (java.sql Connection PreparedStatement ResultSet ResultSetMetaData)
           (java.lang.reflect Field)
           (javax.sql DataSource)))

(declare unqualified-key)

;;
;; Protocols
;;

(defprotocol RowCompiler
  (compile-row [this cols]))

(defprotocol BatchHandler
  (batch-size [this])
  (handle-batch [this batch]))

(defprotocol IntoConnection
  (^Connection into-connection [this]))

(extend-protocol IntoConnection
  Connection
  (into-connection [this] this)

  DataSource
  (into-connection [this] (.getConnection this)))

;;
;; Implementation
;;

(defn- infer-params [sql]
  (repeat (count (filter (partial = \?) sql)) nil))

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
        (let [sym (gensym "DBEntry")
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
  (fn [^ResultSet rs]
    (reduce
      (fn [acc [i k]]
        (assoc acc k (.getObject rs ^Integer i)))
      nil
      cols)))

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

(defn- prepare! [^PreparedStatement ps params]
  (when params
    (let [it (clojure.lang.RT/iter params)]
      (loop [i 1]
        (when (.hasNext it)
          (.setObject ps i (.next it))
          (recur (inc i)))))))

(defn- -compile
  [batch? ^String sql {:keys [row key con params size] :or {key (unqualified-key), size 100}}]
  (let [row (if con
              (let [ps (.prepareStatement ^Connection con sql)]
                (prepare! ps (or params (infer-params sql)))
                (let [rs (.executeQuery ps)]
                  (let [cols (col-map rs key)
                        row (cond
                              (satisfies? RowCompiler row) (compile-row row (map second cols))
                              row row
                              :else (rs->map-of-cols cols))]
                    (.close ps)
                    row)))
              row)]
    (cond

      (and batch? row)
      (fn compile-static-batch
        ([^Connection con callback]
         (compile-static-batch con callback nil))
        ([^Connection con callback params]
         (let [ps (.prepareStatement con sql)
               count (atom 0)]
           (try
             (prepare! ps params)
             (let [rs (.executeQuery ps)]
               (loop [i 1, res []]
                 (cond
                   (.next rs)
                   (let [res (conj res (row rs))]
                     (swap! count inc)
                     (if (= i size)
                       (do (callback res) (recur 1 []))
                       (recur (inc i) res)))
                   (seq res)
                   (callback res))))
             @count
             (finally
               (.close ps))))))

      row
      (fn compile-static
        ([^Connection con]
         (compile-static con nil))
        ([^Connection con params]
         (let [ps (.prepareStatement con sql)]
           (try
             (prepare! ps params)
             (let [rs (.executeQuery ps)]
               (loop [res []]
                 (if (.next rs)
                   (recur (conj res (row rs)))
                   res)))
             (finally
               (.close ps))))))

      batch?
      (fn compile-dynamic-batch
        ([^Connection con callback]
         (compile-dynamic-batch con callback nil))
        ([^Connection con callback params]
         (let [ps (.prepareStatement con sql)
               count (atom 0)]
           (try
             (prepare! ps params)
             (let [rs (.executeQuery ps)
                   cols (col-map rs key)
                   row (rs->map-of-cols cols)]
               (loop [i 1, res []]
                 (cond
                   (.next rs)
                   (let [res (conj res (row rs))]
                     (swap! count inc)
                     (if (= i size)
                       (do (callback res) (recur 1 []))
                       (recur (inc i) res)))
                   (seq res)
                   (callback res))))
             @count
             (finally
               (.close ps))))))

      :else
      (fn compile-dynamic
        ([^Connection con]
         (compile-dynamic con nil))
        ([^Connection con params]
         (let [ps (.prepareStatement con sql)]
           (try
             (prepare! ps params)
             (let [rs (.executeQuery ps)
                   cols (col-map rs key)
                   row (rs->map-of-cols cols)]
               (loop [res []]
                 (if (.next rs)
                   (recur (conj res (row rs)))
                   res)))
             (finally
               (.close ps)))))))))

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
;; Compiler
;;

(defn compile
  "Given a SQL String and optional options, compiles a query into an effective
  function of `connection ?params -> results`. Accepts the following options:

  | key          | description |
  | -------------|-------------|
  | `:row`       | Optional function of `rs->value` or a [[RowCompiler]] to convert rows into values
  | `:key`       | Optional function of `rs-meta i->key` to create key for map-results
  | `:con`       | Optional database connection to extract rs-meta at query compile time
  | `:params`    | Optional parameters for extracting rs-meta at query compile time"
  ([sql] (-compile nil sql nil))
  ([sql opts] (-compile nil sql opts)))

(defn compile-batch
  "Given a SQL String and optional options, compiles a query into an effective batching
  function of `connection callback ?params`, which calls the `callback` argument for each full
  (and the final maybe not full) batch of results.

  Accepts the following options:

  | key          | description |
  | -------------|-------------|
  | `:row`       | Optional function of `rs->value` or a [[RowCompiler]] to convert rows into values
  | `:key`       | Optional function of `rs-meta i->key` to create key for map-results
  | `:size`      | Optional size of the batch (default 100)
  | `:con`       | Optional database connection to extract rs-meta at query compile time
  | `:params`    | Optional parameters for extracting rs-meta at query compile time"
  ([sql] (-compile true sql nil))
  ([sql opts] (-compile true sql opts)))
