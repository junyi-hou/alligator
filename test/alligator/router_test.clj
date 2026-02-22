(ns alligator.router-test
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [alligator.router :as router]
   [alligator.test-utils :refer [reset-all-states]]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each reset-all-states)

(methods/load-handlers!)

(deftest test-outstanding-client-requests-tracking
  (testing "can store and retrieve request mappings"
    (let [m (mux/create-multiplexer)
          states (:states m)
          outstanding-client-requests (:outstanding-client-requests states)]
      ;; Store some test data
      (swap! outstanding-client-requests assoc 1 "textDocument/completion")
      (swap! outstanding-client-requests assoc 2 "textDocument/hover")

      ;; Verify storage
      (is (= "textDocument/completion"
             (get @outstanding-client-requests 1)))
      (is (= "textDocument/hover"
             (get @outstanding-client-requests 2))))))

(deftest test-start-dispatching-client-messages!
  (testing "creates publisher and subscribes handlers"
    (let [input-chan (async/chan)
          m (mux/create-multiplexer)
          _ (router/start-dispatching-client-messages! input-chan m)
          ;; Clean up
          _ (async/close! input-chan)
          _ (mux/stop-all-servers! m)]
      ;; If we get here without exceptions, the test passes
      (is true))))

(deftest test-start-processing-server-messages!
  (testing "creates publisher and subscribes handlers"
    (let [output-chan (async/chan)
          m (mux/create-multiplexer)
          _ (router/start-processing-server-messages! output-chan m)
          ;; Clean up
          _ (async/close! output-chan)
          _ (mux/stop-all-servers! m)]
      ;; If we get here without exceptions, the test passes
      (is true))))

(deftest test-get-client-message-topic
  (testing "returns :response for response.result messages"
    (let [message {:jsonrpc "2.0" :id 1 :result {}}
          m (mux/create-multiplexer)
          states (:states m)]
      (is (= :response
             (#'alligator.router/get-client-message-type message states)))))

  (testing "returns :error for response.error messages"
    (let [message {:jsonrpc "2.0" :id 1 :error {:code -32603}}
          m (mux/create-multiplexer)
          states (:states m)]
      (is (= :error
             (#'alligator.router/get-client-message-type message states)))))

  (testing "returns method name for implemented requests"
    (let [message {:jsonrpc "2.0" :id 101 :method "textDocument/completion"}
          m (mux/create-multiplexer)
          states (:states m)]
      (is (= "textDocument/completion"
             (with-redefs [methods/all-client-methods
                           (fn [] '("textDocument/completion" :default))]
               (#'alligator.router/get-client-message-type message states))))))

  (testing "handles illegal messages"
    (let [message {:jsonrpc "2.0"}
          message2 nil
          m (mux/create-multiplexer)
          states (:states m)]
      (is (= :illegal-client-message-type
             (#'alligator.router/get-client-message-type message states)))

      (is (= :illegal-client-message-type
             (#'alligator.router/get-client-message-type message2 states))))))

(deftest test-get-server-message-topic
  (testing "returns implemented method name for server response"
    (let [m (mux/create-multiplexer)
          states (:states m)
          client-message {:jsonrpc "2.0" :id 1 :method "textDocument/completion"}]
      ;; First simulate a client request to populate outstanding-client-requests
      (#'alligator.router/get-client-message-type client-message states)

      ;; Now test server response - should retrieve the method from outstanding-client-requests
      (let [msg {:message {:jsonrpc "2.0" :id 1 :result {}} :from "test-server"}]
        (is (= "textDocument/completion"
               (with-redefs [methods/all-server-methods
                             (fn [] '("textDocument/completion" :default))]
                 (#'alligator.router/get-server-message-type msg states))))))

  (testing "returns :default for unimplemented response method"
    (let [m (mux/create-multiplexer)
          states (:states m)
          client-message {:jsonrpc "2.0" :id 101 :method "unimplemented/method"}]
      (#'alligator.router/get-client-message-type client-message states)

      (let [message {:message {:jsonrpc "2.0" :id 101 :result {}} :from "test-server"}]
        (is (= :default
                               (#'alligator.router/get-server-message-type message states))))))

  (testing "returns implemented method name for server requests"
    (let [m (mux/create-multiplexer)
          states (:states m)
          msg {:message {:jsonrpc "2.0" :id 101 :method "workspace/applyEdit"} :from "test-server"}]
      (is (= "workspace/applyEdit"
             (with-redefs [methods/all-server-methods
                           (fn [] '("workspace/applyEdit" :default))]
               (#'alligator.router/get-server-message-type msg states)))))))

  (testing "returns :default for unimplemented method name for server requests"
    (let [m (mux/create-multiplexer)
          states (:states m)
          msg {:message {:jsonrpc "2.0" :id 101 :method "unimplemented/method"} :from "test-server"}]
      (is (= :default
             (#'alligator.router/get-server-message-type msg states)))))

  (testing "returns :error for response.error messages"
    (let [m (mux/create-multiplexer)
          states (:states m)
          msg {:message {:jsonrpc "2.0" :id 1 :error {:code -32603}} :from "test-server"}]
      (is (= :error
             (#'alligator.router/get-server-message-type msg states)))))

  (testing "handles illegal messages"
    (let [m (mux/create-multiplexer)
          states (:states m)
          msg {:message {:jsonrpc "2.0"} :from "test-server"}]
      (is (= :illegal-server-message-type
             (#'alligator.router/get-server-message-type msg states)))))

  (testing "handles response with unknown id"
    (let [m (mux/create-multiplexer)
          states (:states m)
          msg {:message {:jsonrpc "2.0" :id 999 :result {}} :from "test-server"}]
      (is (= :illegal-server-message-type
             (#'alligator.router/get-server-message-type msg states))))))

(deftest test-uniquify-server-request-id
  (testing "replaces server request ID with UUID and stores mapping"
    (let [m (mux/create-multiplexer)
          states (:states m)
          server-request-id-mapping (:server-request-id-mapping states)
          original-message {:jsonrpc "2.0" :id 42 :method "workspace/configuration"}
          server-name "test-server"
          result (#'alligator.router/uniquify-server-request-id
                  {:message original-message :from server-name}
                  server-request-id-mapping)]

      ;; Check that ID was replaced with UUID string
      (is (not= 42 (:id (:message result))))
      (is (string? (:id (:message result))))

      ;; Check that mapping was stored
      (let [new-id (:id (:message result))
            mapping (get @server-request-id-mapping new-id)]
        (is (= server-name (:server-name mapping)))
        (is (= 42 (:original-id mapping))))))

  (testing "stores multiple mappings correctly"
    (let [m (mux/create-multiplexer)
          states (:states m)
          server-request-id-mapping (:server-request-id-mapping states)
          server-name "test-server"
          req1 {:jsonrpc "2.0" :id 1 :method "workspace/configuration"}
          req2 {:jsonrpc "2.0" :id 2 :method "workspace/executeCommand"}
          result1 (#'alligator.router/uniquify-server-request-id {:message req1 :from server-name} server-request-id-mapping)
          result2 (#'alligator.router/uniquify-server-request-id {:message req2 :from server-name} server-request-id-mapping)
          id1 (:id (:message result1))
          id2 (:id (:message result2))
          mapping1 (get @server-request-id-mapping id1)
          mapping2 (get @server-request-id-mapping id2)]

      ;; Check individual mappings
      (is (= 1 (:original-id mapping1)))
      (is (= 2 (:original-id mapping2)))
      (is (= server-name (:server-name mapping1)))
      (is (= server-name (:server-name mapping2))))))
