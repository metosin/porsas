(ns porsas.core
  (:require [clojure.string :as str])
  (:import (java.lang.reflect Field)
           (clojure.lang PersistentArrayMap)))

(defprotocol RowCompiler
  (compile-row [this cols]))

(defprotocol GetValue
  (get-value [this i]))

(defprotocol Cached
  (cache [this]))

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

(defn ^:no-doc rs->map-of-cols [cols]
  (let [size (* 2 (count cols))]
    (fn [rs]
      (let [a ^objects (make-array Object size)
            iter (clojure.lang.RT/iter cols)]
        (while (.hasNext iter)
          (let [v (.next iter)
                to (* 2 ^int (nth v 1))
                from ^int (nth v 0)]
            (aset a to (nth v 2))
            (aset a (inc to) (get-value rs from))))
        (PersistentArrayMap. a)))))

(defn ^:no-doc rs-> [start pc fields]
  (let [rs (gensym)
        fm (zipmap fields (range start (inc (count fields))))]
    (eval
      `(fn [~rs]
         ~(if pc
            `(~pc ~@(map (fn [[_ v]] `(get-value ~rs ~v)) fm))
            (apply array-map (mapcat (fn [[k v]] [k `(get-value ~rs ~v)]) fm)))))))

;;
;; row
;;

(defn rs->record
  ([record]
   (rs->record record nil))
  ([record {:keys [start] :or {start 1}}]
   (rs-> start (constructor-symbol record) (record-fields record))))

(defn rs->compiled-record
  ([]
   (rs->compiled-record nil))
  ([{:keys [start] :or {start 1}}]
   (reify
     RowCompiler
     (compile-row [_ cols]
       (let [{:keys [->instance fields]} (memoized-compile-record cols)]
         (rs-> start ->instance fields))))))

(defn rs->map
  ([]
   (rs->map nil))
  ([{:keys [start] :or {start 1}}]
   (reify
     RowCompiler
     (compile-row [_ cols]
       (rs-> start nil cols)))))
