(ns porsas.next
  (:require [next.jdbc.result-set :as rs]
            [porsas.jdbc :as pj]
            [porsas.core :as p])
  (:import (java.sql ResultSet)
           (java.util HashMap)))

(defn caching-row-builder
  "A [[next.jdbc.result-set/RowBuilder]] implementation using porsas. WIP."
  ([]
   (caching-row-builder (pj/qualified-key)))
  ([key]
   (let [cache (HashMap.)] ;; TODO: make bounded
     (fn [^ResultSet rs opts]
       (let [sql (:next.jdbc/sql-string opts)
             ->row (or (.get cache sql)
                       (let [->row (p/rs-> 1 nil (map last (pj/col-map rs key)))]
                         (.put cache sql ->row)
                         ->row))]
         (reify
           p/Cached
           (cache [_] (into {} cache))
           rs/RowBuilder
           (->row [_] (->row rs))
           (with-column [_ row _] row)
           (column-count [_] 0)
           (row! [_ row] row)
           rs/ResultSetBuilder
           (->rs [_] (transient []))
           (with-row [_ rs row] (conj! rs row))
           (rs! [_ rs] (persistent! rs))))))))
