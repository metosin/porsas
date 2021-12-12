(ns porsas.cache-test
  (:require [clojure.test :as t]
            [clojure.core.cache.wrapped :as c]
            [porsas.cache :as sut]))

(t/deftest cache
  (let [c (sut/->CoreCache (c/basic-cache-factory {}))]
    (t/is (= {} (sut/elements c)))

    (t/is (= ::a (sut/lookup-or-set c :a (constantly ::a))))
    (t/is (= ::a (sut/lookup-or-set c :a (constantly ::b))))

    (t/is (= {:a ::a} (sut/elements c)))))
