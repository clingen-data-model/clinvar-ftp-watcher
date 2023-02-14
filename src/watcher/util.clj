(ns watcher.util
  "A place for utility functions."
  (:require [clojure.data.json  :as json]
            [clojure.java.shell :as shell]
            [clojure.pprint     :refer [pprint]]
            [clojure.string     :as str]))

(defmacro dump
  "Dump [EXPRESSION VALUE] where VALUE is EXPRESSION's value."
  [expression]
  `(let [x# ~expression]
     (do
       (pprint ['~expression x#])
       x#)))

;; Wrap an authorization header around Bearer TOKEN.
;;
(defn auth-header
  "Return an Authorization header map with bearer TOKEN."
  [token]
  {"Authorization" (str/join \space ["Bearer" token])})

(defn shell
  "Run ARGS in a shell and return stdout or throw."
  [& args]
  (let [{:keys [exit err out]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (format "injest: %s exit status from: %s : %s"
                              exit args err))))
    (str/trim out)))

(defn parse-json
  "Parse STREAM as JSON or print it."
  [stream]
  (try (json/read stream :key-fn keyword)
       (catch Throwable x
         (pprint {:exception x :stream (slurp stream)})
         stream)))

(defn getenv
  "Throw or return the value of environment VARIABLE in process."
  [variable]
  (let [result (System/getenv variable)]
    (when-not result
      (throw (ex-info (format "Set the %s environment variable" variable) {})))
    result))
