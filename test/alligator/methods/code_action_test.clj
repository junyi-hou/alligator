(ns alligator.methods.code-action-test
  (:require
   [alligator.methods :as methods]
   [alligator.methods.code-action :as code-action]
   [alligator.multiplexer :as mux]
   [alligator.test-utils :refer [reset-all-states]]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing use-fixtures]]))

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
    (let [in-chan (async/chan)
          out-chan (async/chan)
          m (mux/create-multiplexer)]

      (mux/add-server! m "s1" ["cat"])
      (mux/add-server! m "s2" ["cat"] [:code-action-provider])

      (methods/process-server-message "textDocument/codeAction" in-chan out-chan m)

      (async/>!! in-chan {:from "s1"
                          :message {:jsonrpc "2.0"
                                    :id 100
                                    :result [{:title "Foo" :kind "quickfix" :edit "some-file"}
                                             {:title "Baz" :command "baz" :arguments ["1" "2"]}]}})
      (async/>!! in-chan {:from "s2"
                          :message {:jsonrpc "2.0"
                                    :id 100
                                    :result [{:title "Bar" :kind "refactor.inline" :command {:title "bar command" :command "bar"} :data {:key "value"}}]}})
      (let [response (async/<!! out-chan)]
        (is (= 100 (:id response)))
        (is (= (set (:result response))
               (set [{:title "Foo" :kind "quickfix" :edit "some-file" :data {:alligator-source "s1"}}
                     {:title "Bar" :kind "refactor.inline" :command {:title "bar command" :command "bar"} :data {:key "value" :alligator-source "s2"}}
                     {:title "Baz" :command "baz" :arguments ["1" "2"]}]))))
      (mux/stop-all-servers! m))))

(deftest test-process-client-message-resolve
  (testing "Relay codeAction/resolve request to the correct server"
    (let [alligator-out-chan (async/chan)
          client-out-chan (async/chan)
          m (mux/create-multiplexer alligator-out-chan)]
      (mux/add-server! m "s1" ["cat"])
      (mux/add-server! m "s2" ["cat"] [:code-action-provider])

      (methods/process-client-message "codeAction/resolve" client-out-chan m)

      (let [message {:jsonrpc "2.0"
                     :id 101
                     :method "codeAction/resolve"
                     :params {:title "Foo" :kind "quickfix" :edit "some-file" :data {:alligator-source "s1"}}}]
        (async/>!! client-out-chan message)

        (let [relayed-message (async/<!! alligator-out-chan)
              expected-message (assoc-in message [:params :data] {})]
          (is (= expected-message (:message relayed-message)))))
      (mux/stop-all-servers! m))))
