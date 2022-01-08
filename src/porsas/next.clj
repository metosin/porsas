(ns porsas.next
  (:require [next.jdbc.result-set :as rs]
            [porsas.cache :as cache]
            [porsas.core :as p]
            [porsas.jdbc :as pj])
  (:import (java.sql ResultSet)))

(defn caching-row-builder
  "A [[next.jdbc.result-set/RowBuilder]] implementation using porsas. WIP."
  ([]
   (caching-row-builder (pj/qualified-key)))
  ([key & {:keys [cache]}]
   (let [cache (or cache ((requiring-resolve 'porsas.cache.caffeine/create-cache)))]
     (fn [^ResultSet rs opts]
       (let [sql   (:next.jdbc/sql-params opts)
             ->row (cache/lookup-or-set cache sql (fn [_] (p/rs-> 1 nil (map last (pj/col-map rs key)))))]
         (reify
           cache/Cached
           (cache [_] (into {} cache))
           rs/RowBuilder
           (->row [_] (->row rs))
           (with-column [_ row _] row)
           (with-column-value [_ row _ _] row)
           (column-count [_] 0)
           (row! [_ row] row)
           rs/ResultSetBuilder
           (->rs [_] (transient []))
           (with-row [_ rs row] (conj! rs row))
           (rs! [_ rs] (persistent! rs))
           clojure.lang.ILookup ; only supports :cols and :rsmeta
           (valAt [this k] (get this k nil))
           (valAt [this k not-found]
             (case k
               :cols   (map last (pj/col-map rs key))
               :rsmeta (.getMetaData rs)
               not-found))))))))
