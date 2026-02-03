(ns alligator.methods-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [alligator.methods :as methods]
            [alligator.multiplexer :as mux]
            [clojure.java.io :as io]))

(defn reset-servers-fixture [f]
  ;; Clear enabled-servers before each test
  (reset! mux/enabled-servers [])
  (f))

(use-fixtures :each reset-servers-fixture)

(deftest test-ns-from-file
  (testing "converts file path to namespace symbol"
    (let [file (io/file "src/alligator/methods/diagnostics.clj")
          ns-sym (#'alligator.methods/ns-from-file file)]
      (is (= 'alligator.methods.diagnostics ns-sym)))

    (testing "handles nested directories"
      (let [file (io/file "src/alligator/methods/subdir/handler.clj")
            ns-sym (#'alligator.methods/ns-from-file file)]
        (is (= 'alligator.methods.subdir.handler ns-sym))))))
