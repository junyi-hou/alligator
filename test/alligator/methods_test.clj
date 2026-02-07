(ns alligator.methods-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [alligator.methods :as methods]
            [clojure.java.io :as io]
            [alligator.test-utils :refer [reset-all-states]]))

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
