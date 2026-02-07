(ns alligator.methods.code-action-test
  (:require
   [alligator.methods :as methods]
   [alligator.methods.code-action :as code-action]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [alligator.test-utils :refer [reset-all-states]]))

(use-fixtures :each reset-all-states)

(methods/load-handlers!)

(deftest test-interject-code-action-source
  (testing "Add origingating server to the :data field"
    (let [server-name "server-1"
          result [;; no :data field
                  {:title "Foo" :kind "quickfix" :edit "some-file"}
                  ;; has :data field
                  {:title "Bar" :kind "refactor.inline" :command {:title "bar command" :command "bar"} :data {:key "value"}}
                  ;; raw command
                  {:title "Baz" :command "baz" :arguments ["1" "2"]}]]
      (is (= (#'code-action/interject-code-action-source result server-name)
             [{:title "Foo" :kind "quickfix" :edit "some-file" :data {:alligator-source server-name}}
              {:title "Bar" :kind "refactor.inline" :command {:title "bar command" :command "bar"} :data {:key "value" :alligator-source server-name}}
              {:title "Baz" :command "baz" :arguments ["1" "2"]}])))))

(deftest test-process-server-message-code-action
  (testing "Merge code action response using conj[oin]"
    (let [in-chan (async/chan 10)
          out-chan (async/chan 10)
          server1 {:name "s1" :command "c1" :is-default true :capabilities nil}
          server2 {:name "s2" :command "c2" :is-default false :capabilities [:code-action-provider]}]
      (reset! mux/enabled-servers [server1 server2])

      ;; open code action channel
      (methods/process-server-message "textDocument/codeAction" in-chan out-chan)

      (async/go
        (async/>! in-chan {:from "s1"
                           :message {:jsonrpc "2.0"
                                     :id 100
                                     :result [{:title "Foo" :kind "quickfix" :edit "some-file"}
                                              {:title "Baz" :command "baz" :arguments ["1" "2"]}]}})
        (async/>! in-chan {:from "s2"
                           :message {:jsonrpc "2.0"
                                     :id 100
                                     :result [{:title "Bar" :kind "refactor.inline" :command {:title "bar command" :command "bar"} :data {:key "value"}}]}})
        (let [response (async/<!! out-chan)]
          (is (= 100 (:id response)))
          (is (= (set (:result response))
                 (set [{:title "Foo" :kind "quickfix" :edit "some-file" :data {:alligator-source "s1"}}
                       {:title "Bar" :kind "refactor.inline" :command {:title "bar command" :command "bar"} :data {:key "value" :alligator-source "s2"}}
                       {:title "Baz" :command "baz" :arguments ["1" "2"]}]))))))))

;; (deftest test-process-client-message-resolve
;;   (testing ""))
(deftest test-process-client-message-resolve
  (testing "Relay codeAction/resolve request to the correct server"
    (let [server1-in-chan (async/chan 10)
          client-out-chan (async/chan 10)
          server1 {:name "s1" :command "c1" :stdin server1-in-chan :is-default true :capabilities nil}
          server2 {:name "s2" :command "c2" :stdin (async/chan 1) :is-default false :capabilities [:code-action-provider]}]
      (reset! mux/enabled-servers [server1 server2])

      (methods/process-client-message "codeAction/resolve" client-out-chan)
      (let [message {:jsonrpc "2.0"
                     :id 101
                     :method "codeAction/resolve"
                     :params {:title "Foo" :kind "quickfix" :edit "some-file" :data {:alligator-source "s1"}}}]
        (async/>!! client-out-chan message)

        (let [relayed-message (async/<!! server1-in-chan)
              expected-message (assoc-in message [:params :data] {})]
          (is (= expected-message relayed-message)))))))
