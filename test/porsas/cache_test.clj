(ns porsas.cache-test
  (:require [clojure.test :as t]
            [clojure.template :refer [do-template]]
            [porsas.cache :as sut]
            [porsas.cache.core :as core]
            [porsas.cache.caffeine :as caffeine]))

(t/deftest cache
  (do-template
    [t cache]
    (let [c cache]
      (t/testing t
        (t/is (= {} (sut/elements c)))

        (t/is (= ::a (sut/lookup-or-set c :a (constantly ::a))))
        (t/is (= ::a (sut/lookup-or-set c :a (constantly ::b))))

        (t/is (= {:a ::a} (sut/elements c)))))
    "core cache" (core/create-cache)
    "caffeine" (caffeine/create-cache)))
