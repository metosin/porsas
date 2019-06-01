# porsas [![cljdoc badge](https://cljdoc.xyz/badge/metosin/porsas)](https://cljdoc.xyz/jump/release/metosin/porsas)

<img src="./docs/images/logo.png" width=180 align="right"/>

> Nopea kuin sika pakkasella

Spike to see how fast we can go with Clojure + JDBC. Highly Experimental.

Related dicsussion: https://clojureverse.org/t/next-jdbc-early-access/4091

## Latest version

[![Clojars Project](http://clojars.org/metosin/porsas/latest-version.svg)](http://clojars.org/metosin/porsas)

## Usage

`porsas` provides tools for precompiling the functions to convert `ResultSet` into Clojure values. This enables basically Java-fast JDBC queries while using idiomatic Clojure.

```clj
(defprotocol DataMapper
  (cache [this])
  (query-one [this ^Connection connection sqlvec])
  (query [this ^Connection connection sqlvec]))
```

`porsas.core/compile` returns a `porsas.core/DataMapper` instance. The following options are available for the compiler:

| key           | description |
| --------------|-------------|
| `:row`        | Optional function of `rs->value` or a [[RowCompiler]] to convert rows into values
| `:key`        | Optional function of `rs-meta i->key` to create key for map-results"

Note: some `RowCompiler` implementations (like `p/rs->map`) generate the code at runtime, which might not supported in all platforms like [GraalVM](https://www.graalvm.org/).

### Examples

#### A Java JDBC query

```clj
;; 630ns
(title "java")
(bench! (java-query connection "SELECT * FROM fruit"))
```

#### Compiled query functions

```clj
(def mapper (p/data-mapper {:row (p/rs->map)}))

;; 630ns
(title "porsas: compiled & cached query")
(bench! (p/query mapper connection "SELECT * FROM fruit")))
```

### Cached query functions

With defaults, a bit slower (non-compiled) mapper is used. Works on all platforms.

```clj
;; 1300ns
(title "porsas: cached query")
(bench! (p/query p/default-mapper connection "SELECT * FROM fruit")))
```

### Fully Dynamic queries

```clj
(def mapper (p/data-mapper {:cache nil)}))

;; 1500ns
(title "porsas: dynamic query")
(bench! (p/query mapper connection "SELECT * FROM fruit"))
```

## Performance

At least an order of magnitude faster than [`clojure.java.jdbc`](https://github.com/clojure/java.jdbc), see [the tests](https://github.com/metosin/porsas/blob/master/test/porsas/core_test.clj) for more details.

<img src="./docs/images/porsas.png"/>

## TODO

* more tests
* batch-api
* async-api for postgresql?

## License

Copyright © 2019 [Metosin Oy](http://www.metosin.fi)

Distributed under the Eclipse Public License, the same as Clojure.
