;; Copyright 2013 Andrey Antukh <niwi@niwi.be>
;;
;; Licensed under the Apache License, Version 2.0 (the "License")
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns jdbc.types.connection)

(defrecord Connection [connection metadata]
  java.lang.AutoCloseable
  (close [this]
    (.close (:connection this))))

(defn vendor-name
  "Get connection vendor name."
  [^Connection c]
  {:pre [(instance? Connection c)]}
  (.getDatabaseProductName (:metadata c)))

(defn is-readonly?
  "Returns true if a current connection is
  in read-only model."
  [^Connection c]
  {:pre [(instance? Connection c)]}
  (.isReadOnly (:connection c)))

(defn is-connection?
  "Test if a value is a connection
  instance."
  [c]
  (instance? Connection c))
