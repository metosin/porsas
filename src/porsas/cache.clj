(ns porsas.cache
  (:require [clojure.core.cache.wrapped :as c]))

(defprotocol Cache
  (lookup-or-set [this k value-fn] "Lookup a value in the cache based on `k` and if not found set its value based on `value-fn` and returns it.")
  (elements [this] "Returns the actual state of the cache."))

(defprotocol Cached
  (cache [this]))

(defrecord CoreCache [a]
    Cache
    (lookup-or-set [this k value-fn]
      (c/lookup-or-miss (:a this) k value-fn))
    (elements [this] (into {} @(:a this))))

(defn create-cache []
  (->CoreCache (c/basic-cache-factory {})))
