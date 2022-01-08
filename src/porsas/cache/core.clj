(ns porsas.cache.core
  (:require [clojure.core.cache.wrapped :as c]
            [porsas.cache :as pc]))

(defrecord CoreCache [c]
  pc/Cache
  (lookup-or-set [this k value-fn]
    (c/lookup-or-miss (:c this) k value-fn))
  (elements [this] (into {} @(:c this))))

(defn create-cache []
  (->CoreCache (c/lru-cache-factory {})))
