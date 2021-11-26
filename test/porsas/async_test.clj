(ns porsas.async-test
  (:require
   [clojure.test :as t]
   [porsas.async :as pa]
   [promesa.core :as p]
   [manifold.deferred :as d]))

(t/deftest async
  (let [pool (pa/pool
              {:uri      "postgresql://localhost:5432/porsas"
               :user     "user"
               :password "password"})]
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
