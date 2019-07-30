(ns porsas.async-test
  (:require [porsas.async :as pa]))

(def pool
  (pa/pool
    {:uri "postgresql://localhost:5432/hello_world"
     :user "benchmarkdbuser"
     :password "benchmarkdbpass"}))

(-> (pa/query-one pool ["SELECT randomnumber from WORLD where id=$1" 1])
    (pa/then :randomnumber))

;;
;; promesa
;;

(require '[promesa.core :as p])

(-> (pa/query-one pool ["SELECT randomnumber from WORLD where id=$1" 1])
    (p/chain :randomnumber)
    (deref))

;;
;; manifold
;;

(require '[manifold.deferred :as d])

(-> (pa/query-one pool ["SELECT randomnumber from WORLD where id=$1" 1])
    (d/chain :randomnumber)
    (deref))
