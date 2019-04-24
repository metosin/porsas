# OLD

This guide is for the first iteration, where the api had explicit precompile step.

## Usage

`porsas` adds clean separation between JDBC query compilation and execution. Queries are compiled (optionally against a live database), producing optimized query-functions.

```clj
(require '[porsas.core :as p])

;; get a database connection from somewhere
(def connection
  (clojure.java.jdbc/get-connection
    {:dbtype "h2:mem" :dbname "perf"}))
```

Mapping result to a predefined record:

```clj
(defrecord Fruit [id name appearance cost grade])

(def get-fruits
  (p/compile 
    "SELECT * FROM fruit" 
    {:row (p/rs->record Fruit)}))

(get-fruits connection)
;[#user.Fruit{:id 1, :name "Apple", :appearance "red", :cost 59, :grade 87.0}
; #user.Fruit{:id 2, :name "Banana", :appearance "yellow", :cost 29, :grade 92.2}
; #user.Fruit{:id 3, :name "Peach", :appearance "fuzzy", :cost 139, :grade 90.0}
; #user.Fruit{:id 4, :name "Orange", :appearance "juicy", :cost 89, :grade 88.6}]
```

Generating a result record fro the given query:

```clj
(def select-id-name-from-fruit
  (p/compile
    "SELECT id, name FROM fruit"
    {:connection connection
     :row (p/rs->compiled-record)
     :key (p/unqualified-key str/lower-case)}))

(select-id-name-from-fruit connection)
;[#user.DBResult14487{:id 1, :name "Apple"}
; #user.DBResult14487{:id 2, :name "Banana"}
; #user.DBResult14487{:id 3, :name "Peach"}
; #user.DBResult14487{:id 4, :name "Orange"}]
```

Generating maps with simple keys:

```clj
(def get-fruits-map
  (p/compile
    "SELECT * FROM fruit"
    {:connection connection
     :row (p/rs->map)
     :key (p/unqualified-key str/lower-case)}))

(get-fruits-map connection)
;[{:id 1, :name "Apple", :appearance "red", :cost 59, :grade 87.0}
; {:id 2, :name "Banana", :appearance "yellow", :cost 29, :grade 92.2}
; {:id 3, :name "Peach", :appearance "fuzzy", :cost 139, :grade 90.0}
; {:id 4, :name "Orange", :appearance "juicy", :cost 89, :grade 88.6}]
```

Same with qualified keys:

```clj
(def get-fruits-map-qualified
  (p/compile
    "SELECT * FROM fruit"
    {:connection connection
     :row (p/rs->map)
     :key (p/qualified-key str/lower-case)}))

(get-fruits-map-qualified connection)
;[#:fruit{:id 1, :name "Apple", :appearance "red", :cost 59, :grade 87.0}
; #:fruit{:id 2, :name "Banana", :appearance "yellow", :cost 29, :grade 92.2}
; #:fruit{:id 3, :name "Peach", :appearance "fuzzy", :cost 139, :grade 90.0}
; #:fruit{:id 4, :name "Orange", :appearance "juicy", :cost 89, :grade 88.6}]
```

Partial application for a fully dynamic query:

```clj
(def dynamic-get-fruits-map-qualified
  (partial 
    (p/compile 
      "SELECT name, cost FROM fruit" 
      {:key (p/qualified-key str/lower-case)})))

(dynamic-get-fruits-map-qualified connection)
;[#:fruit{:name "Apple", :cost 59}
; #:fruit{:name "Banana", :cost 29}
; #:fruit{:name "Peach", :cost 139}
; #:fruit{:name "Orange", :cost 89}]
```

Parameterized queries:

```clj
(def get-fruits-by-color
  (p/compile
    "SELECT * FROM fruit where appearance = ?"
    {:connection connection
     :row (p/rs->map)
     :key (p/qualified-key str/lower-case)}))

(get-fruits-by-color connection ["red"])
;[#:fruit{:id 1, :name "Apple", :appearance "red", :cost 59, :grade 87.0}]
```

### Streaming results

As JDBC is blocking, so is this (returns the number of rows).

```clj
(def get-fruits-map-qualified-batch
  (p/compile-batch
    "SELECT name FROM fruit"
    {:connection connection
     :size 3
     :row (p/rs->map)
     :key (p/qualified-key str/lower-case)}))

(get-fruits-map-qualified-batch connection (partial println "-->"))
;--> [#:fruit{:name Apple} #:fruit{:name Banana} #:fruit{:name Orange}]
;--> [#:fruit{:name Peach}]
; 4
```
