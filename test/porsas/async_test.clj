(ns porsas.async-test
  (:require
   [clojure.template :refer [do-template]]
   [clojure.test :as t]
   [porsas.async :as pa]
   [promesa.core :as p]
   [manifold.deferred :as d])
  (:import
   (io.vertx.pgclient PgPool)
   (io.vertx.sqlclient SqlClient)))

(def pool-opts {:uri              "postgresql://localhost:5432/porsas"
                :user             "postgres"
                :pipelining-limit 10})

(t/deftest async
  (do-template [test-case client]
    (t/testing test-case
      (t/is (= "Apple"
               (-> (pa/query-one client ["SELECT name from fruit where id=$1" 1])
                   (pa/then :name)
                   (deref))))

      (t/is (= "io.vertx.pgclient.PgException: ERROR: relation \"non_existing\" does not exist (42P01)"
               (-> (pa/query-one client ["SELECT * from non_existing where id=$1" 1])
                   (pa/then :name)
                   (pa/catch #(-> % .getMessage))
                   (deref))))

      (t/is (= ["Apple" "Banana" "Peach" "Orange"]
               (-> (pa/query client ["SELECT name from fruit"])
                   (pa/then-async #(mapv :name %))
                   (deref))))

      (t/testing "promesa"
        (t/is (= "Banana"
                 (-> (pa/query-one client ["SELECT name from fruit where id=$1" 2])
                     (p/chain :name)
                     (deref)))))

      (t/testing "manifold"
        (t/is (= "Peach"
                 (-> (pa/query-one client ["SELECT name from fruit where id=$1" 3])
                     (d/chain :name)
                     (deref))))))

    "pool" (pa/pool pool-opts)
    "pooled client" (pa/pooled-client pool-opts)))

(t/deftest pool
  (do-template [test-case ctor expected-type]
    (t/testing test-case
      (t/testing "creating with option maps"
        (t/is (instance? expected-type (ctor pool-opts)))
        (t/is (instance? expected-type (ctor (pa/vertx) pool-opts)))
        (t/is (instance? expected-type (ctor (pa/vertx) pool-opts {:size 4}))))
      (t/testing "creating with option objects"
        (t/is (instance? expected-type (ctor (pa/options pool-opts))))
        (t/is (instance? expected-type (ctor (pa/vertx) (pa/options pool-opts))))
        (t/is (instance? expected-type (ctor (pa/vertx) (pa/options pool-opts) (pa/->pool-options {:size 4})))))
      (t/testing "creating with mixed options"
        (t/is (instance? expected-type (ctor (pa/vertx) pool-opts (pa/->pool-options {:size 4}))))
        (t/is (instance? expected-type (ctor (pa/vertx) (pa/options pool-opts) {:size 4})))))

    "pool" pa/pool PgPool
    "pooled-client" pa/pooled-client SqlClient))
