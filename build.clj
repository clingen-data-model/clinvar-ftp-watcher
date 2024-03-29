(ns build
  "Build this thing."
  (:require [clojure.pprint          :refer [pprint]]
            [clojure.tools.build.api :as b]))

(defmacro dump
  "Dump [EXPRESSION VALUE] where VALUE is EXPRESSION's value."
  [expression]
  `(let [x# ~expression]
     (do (pprint ['~expression x#]) x#)))

(def defaults
  "The defaults to configure a build."
  {:class-dir  "target/classes"
   :java-opts  ["-Dclojure.main.report=stderr"]
   :main       'watcher.watcher
   :path       "target"
   :project    "deps.edn"
   :target-dir "target/classes"
   :uber-file  "target/watcher.jar"
   :exclude    ["META-INF/license/LICENSE.aix-netbsd.txt"
                "META-INF/license/LICENSE.boringssl.txt"
                "META-INF/license/LICENSE.mvn-wrapper.txt"
                "META-INF/license/LICENSE.tomcat-native.txt"]})

(defn uber
  "Throw or make an uberjar from source."
  [_]
  (let [{:keys [paths] :as basis} (b/create-basis defaults)
        project                   (assoc defaults :basis basis)]
    (b/delete      project)
    (b/copy-dir    (assoc project :src-dirs paths))
    (b/compile-clj (assoc project
                          :src-dirs paths
                          :ns-compile [(:main defaults)]))
    (b/uber        project)))

(comment
  (uber 
  tbl))
