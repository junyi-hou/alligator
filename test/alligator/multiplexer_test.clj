(ns alligator.multiplexer-test
  (:require
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]])
  (:import
   [clojure.core.async.impl.channels ManyToManyChannel]))

(deftest test-start-server
  (let [m (mux/create-multiplexer)
        srv (mux/add-server! m "test-server" ["cat"] [:completion-provider] true)]

    (testing "start-server creates a server with correct structure"
      (is (= "test-server" (:name srv)))
      (is (some? (:proc srv)))
      (is (instance? ManyToManyChannel (:stdin srv)))
      (is (some #{:completion-provider} (:capabilities srv)))
      ;; :* is added automatically
      (is (some #{:*} (:capabilities srv))))

    (testing "server-output is a valid channel"
      (is (some? (mux/get-output-chan m)))
      (is (instance? ManyToManyChannel (mux/get-output-chan m))))

    (testing "start-server forwards output to server-output channel"
      (let [message {:jsonrpc "2.0" :id 1 :camelCase true}]
      ;; Send the message to cat's stdin
        (async/>!! (:stdin srv) message)
      ;; Wait a bit for the message to be echoed back and processed
        (Thread/sleep 200)
        (let [{body :message server :from} (async/poll! (mux/get-output-chan m))]
          (is (some? body))
          (is (= "test-server" server))
          (is (= 1 (:id body)))
          (is (:camel-case body)))))

    ;; Clean up
    (mux/stop-all-servers! m)))

(deftest test-configured-capabilities-from-server-name
  (let [m (mux/create-multiplexer)]
    (mux/add-server! m "server1" ["cat"] [:hover-provider] true)
    (mux/add-server! m "server2" ["cat"] [:completion-provider] false)

    (testing "returns capabilities for non-default server"
      (is (= [:completion-provider :*] (mux/configured-capabilities-from-server-name m "server2"))))

    (testing "returns nil for default server"
      (is (nil? (mux/configured-capabilities-from-server-name m "server1"))))

    (testing "throws exception for non-existent server"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Server not found: nonexistent"
                            (mux/configured-capabilities-from-server-name m "nonexistent"))))
    (mux/stop-all-servers! m)))

(deftest test-is-default-server
  (let [m (mux/create-multiplexer)]
    (mux/add-server! m "server1" ["cat"] [:hover-provider] true)
    (mux/add-server! m "server2" ["cat"] [:completion-provider] false)

    (testing "returns true for default server"
      (is (true? (mux/is-default-server m "server1")))
      (is (false? (mux/is-default-server m "server2"))))

    (testing "throws exception for non-existent server"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Server not found: nonexistent"
                            (mux/is-default-server m "nonexistent"))))
    (mux/stop-all-servers! m)))

(deftest test-server-accept-method
  (let [m (mux/create-multiplexer)]

    (mux/add-server! m "server1" ["cat"] [:hover-provider :*] true)
    (mux/add-server! m "server2" ["cat"] [:completion-provider] false)
    (mux/add-server! m "server3" ["cat"] [:definition-provider :type-definition-provider] false)

    (testing "returns default server and capable servers for supported methods"
      (let [completion-servers (mux/server-accept-method m "textDocument/completion")]
        (is (= 2 (count completion-servers)))
        (is (some #(= "server1" (:name %)) completion-servers))
        (is (some #(= "server2" (:name %)) completion-servers))
        (is (not (some #(= "server3" (:name %)) completion-servers))))

      (let [definition-servers (mux/server-accept-method m "textDocument/definition")]
        (is (= 2 (count definition-servers)))
        (is (some #(= "server1" (:name %)) definition-servers))
        (is (some #(= "server3" (:name %)) definition-servers))
        (is (not (some #(= "server2" (:name %)) definition-servers)))))

    (testing "returns only default server for unsupported methods"
      (let [format-servers (mux/server-accept-method m "textDocument/formatting")]
        (is (= 1 (count format-servers)))
        (is (= "server1" (:name (first format-servers))))))

    (testing "returns default server for any method in provider-methods"
      (let [rename-servers (mux/server-accept-method m "textDocument/rename")]
        (is (= 1 (count rename-servers)))
        (is (= "server1" (:name (first rename-servers))))))

    (testing "returns empty list for unknown methods not in provider-methods"
      (is (= 0 (count (mux/server-accept-method m "unknown/method")))))
    (mux/stop-all-servers! m)))
