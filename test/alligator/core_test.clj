(ns alligator.core-test
  (:require
   [clojure.test :refer [is deftest testing]]
   ;; [alligator.multiplexer :as mux]
   [alligator.core :as core]))

;; (deftest test-start-server
;;   (with-redefs [mux/start-server (fn [name command capabilities default?]
;;                                    {:name name :command command :capabilities capabilities :is-default default?})]))

(deftest test-get-server-config
  (testing "correctly parse server config from cli arguments"
    (let [options {}
          arguments ["--server" "ty" "server" "--alligator.default"
                     "--server" "ruff" "server" "--alligator.capabilities" "definition-provider" "--alligator.capabilities" "color-provider"]]
      (is (= (#'core/get-server-config options arguments)
             {"ty" {"command" ["ty" "server"] "capabilities" [] "is_default" true}
              "ruff" {"command" ["ruff" "server"] "capabilities" ["definition-provider" "color-provider"] "is_default" false}}))))

  (testing "prefer command line config over config file"
    (let [;; TODO: use dedicated config for testing
          options {:config "test/mock/basic/config.yaml"}
          arguments ["--server" "ty" "server" "--alligator.default"]]
      (is (= (#'core/get-server-config options arguments)
             {"ty" {"command" ["ty" "server"] "capabilities" [] "is_default" true}})))))
