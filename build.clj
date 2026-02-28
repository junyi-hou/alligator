(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :refer [sh]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def version (:version (edn/read-string (slurp (io/resource "version.edn")))))
(def class-dir "target/classes")
(def uber-file (format "target/alligator-%s-standalone.jar"  version))

(defn test "Run all the tests." [opts]
  (let [basis    (b/create-basis {:aliases [:test]})
        cmds     (b/java-command
                  {:basis     basis
                   :main      'clojure.main
                   :main-args ["-m" "cognitect.test-runner" "-d" "test"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- uber-opts [opts]
  (b/delete {:path "target"})
  (assoc opts
         :main 'alligator.core
         :uber-file uber-file
         :basis (b/create-basis {})
         :class-dir class-dir
         :src-dirs ["src" "resources"]
         :ns-compile '[alligator.core]))

(defn uber [opts]
  (println "Building uberjar...")
  (let [opts (uber-opts opts)]
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (b/compile-clj opts)
    (b/uber opts))
  opts)

(defn binary [opts]
  (uber opts)
  (let [args ["native-image"
              "-jar" (str "../" uber-file)
              (format "-H:Name=alligator-%s-%s"
                      (-> "os.name"
                          System/getProperty
                          .toLowerCase
                          (.replaceAll " " ""))
                      (-> "os.arch" System/getProperty .toLowerCase))
              "--no-fallback"
              "--initialize-at-build-time"
              "--report-unsupported-elements-at-runtime"]]
    (let [{:keys [exit out err]} (sh "bash" "-c" (str/join " " args) :dir "target")]
      (println out)
      (if (zero? exit)
        (println "Native binary created successfully!")
        (do (println "Build failed:") (println err))))))
