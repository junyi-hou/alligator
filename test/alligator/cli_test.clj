(ns alligator.cli-test
  (:require
   [alligator.cli :as cli]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(deftest test-get-server-config
  (testing "correctly parse server config from cli arguments"
    (let [options {}
          arguments ["ty server" "--default"
                     "--" "ruff server" "--capabilities" "definition-provider" "--capabilities" "color-provider"
                     "--" "codebook-lsp -flag" "-c" "code-action-provider"]]
      (is (= (cli/get-server-config options arguments)
             {"ty" {"command" ["ty" "server"] "capabilities" [] "is_default" true}
              "ruff" {"command" ["ruff" "server"] "capabilities" ["definition-provider" "color-provider"] "is_default" false}
              "codebook-lsp" {"command" ["codebook-lsp" "-flag"] "capabilities" ["code-action-provider"] "is_default" false}}))))

  (testing "prefer command line config over config file"
    (let [options {:config (io/resource "config.toml")}
          arguments ["ty server" "--default"]]
      (is (= (cli/get-server-config options arguments)
             {"ty" {"command" ["ty" "server"] "capabilities" [] "is_default" true}}))))

  (testing "correctly identifies server flags vs. alligator flags"
    (let [options {}
          arguments ["ty --ty-flag" "--default"]]
      (is (= (cli/get-server-config options arguments)
             {"ty" {"command" ["ty" "--ty-flag"] "capabilities" [] "is_default" true}}))))

  (testing "lsp command (and flags) comes after the alligator flag"
    (let [options {}
          arguments ["--default" "ty --ty-flag"]]
      (is (= (cli/get-server-config options arguments)
             {"ty" {"command" ["ty" "--ty-flag"] "capabilities" [] "is_default" true}})))))
