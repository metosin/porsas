(ns porsas.cache.caffeine
  (:require [porsas.cache :as pc])
  (:import [com.github.benmanes.caffeine.cache Caffeine Cache]
           java.util.function.Function))

(defn ->function ^Function [f]
  (reify Function
    (apply [_this t]
      (f t))))

(defrecord CaffeineCache [c]
  pc/Cache
  (lookup-or-set [this k value-fn]
    (.get ^Cache (:c this) k (->function value-fn)))
  (elements [this] (into {} (.asMap ^Cache (:c this)))))

(defn create-cache []
  (->CaffeineCache (-> (Caffeine/newBuilder)
                       (.maximumSize 10000)
                       (.build))))
