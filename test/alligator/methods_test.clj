(ns alligator.methods-test
  (:require
   [alligator.methods :as methods]
   [alligator.test-utils :refer [reset-all-states]]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each reset-all-states)

(deftest test-ns-from-file
  (testing "converts file path to namespace symbol"
    (let [file (io/file "src/alligator/methods/diagnostics.clj")
          ns-sym (#'alligator.methods/ns-from-file file)]
      (is (= 'alligator.methods.diagnostics ns-sym)))

    (testing "handles nested directories"
      (let [file (io/file "src/alligator/methods/subdir/handler.clj")
            ns-sym (#'alligator.methods/ns-from-file file)]
        (is (= 'alligator.methods.subdir.handler ns-sym))))))
