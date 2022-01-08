(ns porsas.cache)

(defprotocol Cache
  (lookup-or-set [this k value-fn] "Lookups a value in the cache based on `k` and if not found sets its value based on `value-fn` and returns it.")
  (elements [this] "Returns the actual state of the cache."))

(defprotocol Cached
  (cache [this]))

