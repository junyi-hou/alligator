(ns alligator.router-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async]
            [alligator.router :as router]
            [alligator.request-states
             :refer [outstanding-client-requests server-request-id-mapping]]
            [alligator.methods :as methods]
            [alligator.test-utils :refer [reset-all-states]]))

(use-fixtures :each reset-all-states)

(methods/load-handlers!)

(deftest test-outstanding-client-requests-tracking
  (testing "outstanding-client-requests atom is initialized"
    (is (some? @outstanding-client-requests)))

  (testing "can store and retrieve request mappings"
    ;; Store some test data
    (swap! outstanding-client-requests assoc 1 "textDocument/completion")
    (swap! outstanding-client-requests assoc 2 "textDocument/hover")

    ;; Verify storage
    (is (= "textDocument/completion"
           (get @outstanding-client-requests 1)))
    (is (= "textDocument/hover"
           (get @outstanding-client-requests 2)))))

(deftest test-start-dispatching-client-messages!
  (testing "creates publisher and subscribes handlers"
    (let [input-chan (async/chan 10)
          _ (router/start-dispatching-client-messages! input-chan)
          ;; Clean up
          _ (async/close! input-chan)]
      ;; If we get here without exceptions, the test passes
      (is true))))

(deftest test-start-processing-server-messages!
  (testing "creates publisher and subscribes handlers"
    (let [output-chan (async/chan 10)
          _ (router/start-processing-server-messages! output-chan)
          ;; Clean up
          _ (async/close! output-chan)]
      ;; If we get here without exceptions, the test passes
      (is true))))

(deftest test-get-client-message-topic
  (testing "returns :response for response.result messages"
    (let [message {:jsonrpc "2.0" :id 1 :result {}}]
      (is (= :response
             (#'alligator.router/get-client-message-type message)))))

  (testing "returns :error for response.error messages"
    (let [message {:jsonrpc "2.0" :id 1 :error {:code -32603}}]
      (is (= :error
             (#'alligator.router/get-client-message-type message)))))

  (testing "returns method name for implemented requests"
    (let [message {:jsonrpc "2.0" :id 101 :method "textDocument/completion"}]
      (is (= "textDocument/completion"
             (with-redefs [methods/all-client-methods
                           (fn [] '("textDocument/completion" :default))]
               (#'alligator.router/get-client-message-type message))))))

  (testing "handles illegal messages"
    (let [message {:jsonrpc "2.0"}
          message2 nil]
      (is (= :illegal-client-message-type
             (#'alligator.router/get-client-message-type message)))

      (is (= :illegal-client-message-type
             (#'alligator.router/get-client-message-type message2))))))

(deftest test-get-server-message-topic
  (testing "returns implemented method name for server response"
    ;; First simulate a client request to populate outstanding-client-requests
    (let [client-message {:jsonrpc "2.0" :id 1 :method "textDocument/completion"}]
      (#'alligator.router/get-client-message-type client-message))

    ;; Now test server response - should retrieve the method from outstanding-client-requests
    (let [server-message {:message {:jsonrpc "2.0" :id 1 :result {}}}]
      (is (= "textDocument/completion"
             (with-redefs [methods/all-server-methods
                           (fn [] '("textDocument/completion" :default))]
               (#'alligator.router/get-server-message-type server-message))))))

  (testing "returns :default for unimplemented response method"
    (let [client-message {:jsonrpc "2.0" :id 101 :method "unimplemented/method"}]
      (#'alligator.router/get-client-message-type client-message))

    (let [message {:message {:jsonrpc "2.0" :id 101 :result {}}}]
      (is (= :default
             (#'alligator.router/get-server-message-type message)))))

  (testing "returns implemented method name for server requests"
    (let [message {:message {:jsonrpc "2.0" :id 101 :method "workspace/applyEdit"}}]
      (is (= "workspace/applyEdit"
             (with-redefs [methods/all-server-methods
                           (fn [] '("workspace/applyEdit" :default))]
               (#'alligator.router/get-server-message-type message))))))

  (testing "returns :default for unimplemented method name for server requests"
    (let [message {:message {:jsonrpc "2.0" :id 101 :method "unimplemented/method"}}]
      (is (= :default
             (#'alligator.router/get-server-message-type message)))))

  (testing "returns :error for response.error messages"
    (let [message {:message {:jsonrpc "2.0" :id 1 :error {:code -32603}}}]
      (is (= :error
             (#'alligator.router/get-server-message-type message)))))

  (testing "handles illegal messages"
    (let [message {:message {:jsonrpc "2.0"}}]
      (is (= :illegal-server-message-type
             (#'alligator.router/get-server-message-type message)))))

  (testing "handles response with unknown id"
    (let [message {:message {:jsonrpc "2.0" :id 999 :result {}}}]
      (is (= :illegal-server-message-type
             (#'alligator.router/get-server-message-type message))))))

(deftest test-uniquify-server-request-id
  (testing "replaces server request ID with UUID and stores mapping"
    (let [original-message {:jsonrpc "2.0" :id 42 :method "workspace/configuration"}
          server-name "test-server"
          result (#'alligator.router/uniquify-server-request-id
                  {:message original-message :from server-name})]

;; Check that ID was replaced with UUID string
      (is (not= 42 (:id (:message result))))
      (is (string? (:id (:message result))))

      ;; Check that mapping was stored
      (let [new-id (:id (:message result))
            mapping (get @server-request-id-mapping new-id)]
        (is (= server-name (:server-name mapping)))
        (is (= 42 (:original-id mapping))))))

  (testing "stores multiple mappings correctly"
    (reset! server-request-id-mapping {})
    (let [server-name "test-server"
          req1 {:jsonrpc "2.0" :id 1 :method "workspace/configuration"}
          req2 {:jsonrpc "2.0" :id 2 :method "workspace/executeCommand"}
          result1 (#'alligator.router/uniquify-server-request-id {:message req1 :from server-name})
          result2 (#'alligator.router/uniquify-server-request-id {:message req2 :from server-name})]

      ;; Check that both mappings exist
      (is (= 2 (count @server-request-id-mapping)))

      ;; Check individual mappings
      (let [id1 (:id (:message result1))
            id2 (:id (:message result2))
            mapping1 (get @server-request-id-mapping id1)
            mapping2 (get @server-request-id-mapping id2)]
        (is (= 1 (:original-id mapping1)))
        (is (= 2 (:original-id mapping2)))
        (is (= server-name (:server-name mapping1)))
        (is (= server-name (:server-name mapping2)))))))
