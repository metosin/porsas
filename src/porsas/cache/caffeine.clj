(ns porsas.cache.caffeine
  (:require [cloffeine.cache :as cache]
            [porsas.cache :as pc]))

(defrecord CaffeineCache [c]
  pc/Cache
  (lookup-or-set [this k value-fn]
    (cache/get (:c this) k value-fn))
  (elements [this] (into {} (cache/as-map (:c this)))))

(defn create-cache []
  (->CaffeineCache (cache/make-cache)))
