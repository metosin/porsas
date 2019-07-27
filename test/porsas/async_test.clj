(ns porsas.async-test
  (:require [porsas.async :as pa]))

(def pool
  (pa/pool
    {:uri "postgresql://localhost:5432/hello_world"
     :user "benchmarkdbuser"
     :password "benchmarkdbpass"}))

(def mapper (pa/data-mapper))

(-> (pa/query-one mapper pool ["SELECT randomnumber from WORLD where id=$1" 1])
    (pa/then :randomnumber)
    (deref))
