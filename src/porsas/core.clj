(ns porsas.core
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str])
  (:import (java.sql Connection PreparedStatement ResultSet)
           (java.lang.reflect Field)
           (javax.sql DataSource)))

(defn- constructor-symbol [^Class record]
  (let [parts (str/split (.getName record) #"\.")]
    (-> (str (str/join "." (butlast parts)) "/->" (last parts))
        (str/replace #"_" "-")
        (symbol))))

(defn- map-value [keys]
  (zipmap keys (repeat nil)))

(defn record-fields [cls]
  (for [^Field f (.getFields ^Class cls)
        :when (and (= Object (.getType f))
                   (not (.startsWith (.getName f) "__")))]
    (.getName f)))

(def ^:private memoized-compile-record
  (memoize
    (fn [keys]
      (if (some qualified-keyword? keys)
        (map-value keys)
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
  (let [rs (gensym)]
    (eval
      `(fn [~(with-meta rs {:tag 'java.sql.ResultSet})]
         (~pc ~@(map (fn [i] `(.getObject ~rs ^Integer ~i)) (range 1 (inc (count fields)))))))))

(defn- rs->record [pc mc]
  (rs-> pc (keys (mc {}))))

(defn- get-column-names [^ResultSet rs]
  (let [rsmeta (.getMetaData rs)
        idxs (range 1 (inc (.getColumnCount rsmeta)))]
    (mapv (fn [^Integer i] (keyword (.getColumnLabel rsmeta i))) idxs)))

(defn- col-map [^ResultSet rs]
  (loop [i 1, acc [], [n & ns] (get-column-names rs)]
    (if n (recur (inc i) (conj acc [i n]) ns) acc)))

(defn- set-params! [^PreparedStatement ps params]
  (when params
    (let [it (clojure.lang.RT/iter params)]
      (loop [i 1]
        (when (.hasNext it)
          (.setObject ps i (.next it))
          (recur (inc i)))))))

;;
;; Protocols
;;

(defprotocol RowCompiler
  (compile-row [this cols]))

(defprotocol IntoConnection
  (into-connection [this]))

(extend-protocol IntoConnection
  Connection
  (into-connection [this] this)

  DataSource
  (into-connection [this] (.getConnection this)))

;;
;; Public API
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
       (let [rs (gensym)]
         (eval
           `(fn [~(with-meta rs {:tag 'java.sql.ResultSet})]
              ~(apply array-map (mapcat (fn [[k v]] [k `(.getObject ~rs (inc ~v))]) (zipmap cols (range)))))))))))


(defn compile
  ([^String sql]
   (compile sql nil))
  ([^String sql {:keys [row con params]}]
   (if-let [row (if con
                  (let [ps (.prepareStatement ^Connection con sql)]
                    (set-params! ps params)
                    (let [rs (.executeQuery ps)]
                      (let [cols (col-map rs)
                            row (cond
                                  (satisfies? RowCompiler row) (compile-row row (map second cols))
                                  row row
                                  :else (rs->map-of-cols cols))]
                        (.close ps)
                        row)))
                  row)]
     (fn compile-static
       ([^Connection con]
        (compile-static con nil))
       ([^Connection con params]
        (let [ps (.prepareStatement con sql)]
          (try
            (set-params! ps params)
            (let [rs (.executeQuery ps)]
              (loop [res []]
                (if (.next rs)
                  (recur (conj res (row rs)))
                  res)))
            (finally
              (.close ps))))))
     (fn compile-dynamic
       ([^Connection con]
        (compile-dynamic con nil))
       ([^Connection con params]
        (let [ps (.prepareStatement con sql)]
          (try
            (set-params! ps params)
            (let [rs (.executeQuery ps)]
              (let [cols (col-map rs)
                    row (rs->map-of-cols cols)]
                (loop [res nil]
                  (if (.next rs)
                    (recur (conj res (row rs)))
                    res))))
            (finally
              (.close ps)))))))))
