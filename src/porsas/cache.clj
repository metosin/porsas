(ns porsas.cache
  (:require [clojure.core.cache.wrapped :as c]
            [cloffeine.cache :as cache]))

(defprotocol Cache
  (lookup-or-set [this k value-fn] "Lookup a value in the cache based on `k` and if not found set its value based on `value-fn` and returns it.")
  (elements [this] "Returns the actual state of the cache."))

(defprotocol Cached
  (cache [this]))

(defrecord CoreCache [c]
  Cache
  (lookup-or-set [this k value-fn]
    (c/lookup-or-miss (:c this) k value-fn))
  (elements [this] (into {} @(:c this))))

(defn create-cache []
  (->CoreCache (c/lru-cache-factory {})))

(defrecord CaffeineCache [c]
  Cache
  (lookup-or-set [this k value-fn]
    (cache/get (:c this) k value-fn))
  (elements [this] (into {} (cache/as-map (:c this)))))

(defn create-caffeine-cache []
  (->CaffeineCache (cache/make-cache)))
