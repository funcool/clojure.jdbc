(ns jdbc.pool
  "clj.jdbc has native support for more than one connection
  pool implementations.

  Currently it has support for:
  - c3p0 (jdbc.pool.c3p0 namespace)
  - bonecp (jdbc.pool.bonecp namespace)

  Each wrapped connection pool interface only implements
  one function: `make-datasource-spec` that receves a default
  plain dbspec and returns other dbspect with datasource
  instance.

  Example:

    (require '(jdbc.pool.c3p0 :refer [make-datasource-spec]))
    (def dbspec (atom {:classname \"org.postgresql.Driver\"
                       :subprotocol \"postgresql\"
                       :subname \"//localhost:5432/test\"}))
    (swap! dbspec make-datasource-spec)
  "
  (:require [jdbc :refer [strip-jdbc parse-properties-uri]])
  (:import (java.net URI))
  (:gen-class))

(defn normalize-dbspec
  "Normalizes a dbspec in one common format usefull for
  plain connections or connection pool implementations."
  [dbspec]
  (cond
    (string? dbspec)
    (normalize-dbspec (URI. (strip-jdbc dbspec)))

    (instance? URI dbspec)
    (parse-properties-uri dbspec)

    :else db-spec))

(defn make-datasource-spec
  "This implements a dummy connection pool. Really does
  nothing and returns dbspec as is."
  [dbspec] dbspec)
