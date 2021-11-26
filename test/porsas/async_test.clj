(ns porsas.async-test
  (:require
   [clojure.test :as t]
   [porsas.async :as pa]
   [promesa.core :as p]
   [manifold.deferred :as d])
  (:import io.vertx.pgclient.PgPool))

(def pool-opts {:uri      "postgresql://localhost:5432/porsas"
                :user     "user"
                :password "password"})

(t/deftest async
  (let [pool (pa/pool pool-opts)]
    (t/is (= "Apple"
             (-> (pa/query-one pool ["SELECT name from fruit where id=$1" 1])
                 (pa/then :name)
                 (deref))))

    (t/is (= "io.reactiverse.pgclient.PgException: relation \"non_existing\" does not exist"
             (-> (pa/query-one pool ["SELECT * from non_existing where id=$1" 1])
                 (pa/then :name)
                 (pa/catch #(-> % .getMessage))
                 (deref))))

    (t/is (= ["Apple" "Banana" "Peach" "Orange"]
             (-> (pa/query pool ["SELECT name from fruit"])
                 (pa/then-async #(mapv :name %))
                 (deref))))

    (t/testing "promesa"
      (t/is (= "Banana"
               (-> (pa/query-one pool ["SELECT name from fruit where id=$1" 2])
                   (p/chain :name)
                   (deref)))))

    (t/testing "manifold"
      (t/is (= "Peach"
               (-> (pa/query-one pool ["SELECT name from fruit where id=$1" 3])
                   (d/chain :name)
                   (deref)))))))

(t/deftest pool
  (t/testing "creating with option maps"
    (t/is (instance? PgPool (pa/pool pool-opts)))
    (t/is (instance? PgPool (pa/pool (pa/vertx) pool-opts)))
    (t/is (instance? PgPool (pa/pool (pa/vertx) pool-opts {:size 4}))))
  (t/testing "creating with option objects"
    (t/is (instance? PgPool (pa/pool (pa/options pool-opts))))
    (t/is (instance? PgPool (pa/pool (pa/vertx) (pa/options pool-opts))))
    (t/is (instance? PgPool (pa/pool (pa/vertx) (pa/options pool-opts) (pa/->pool-options {:size 4})))))
  (t/testing "creating with mixed options"
    (t/is (instance? PgPool (pa/pool (pa/vertx) pool-opts (pa/->pool-options {:size 4}))))
    (t/is (instance? PgPool (pa/pool (pa/vertx) (pa/options pool-opts) {:size 4})))))
