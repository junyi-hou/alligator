(ns alligator.multiplexer-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.core.async :as async]
   [alligator.multiplexer :as multiplexer])
  (:import [clojure.core.async.impl.channels ManyToManyChannel]))

(defn reset-servers-fixture [f]
  ;; Clear enabled-servers before each test
  (reset! multiplexer/enabled-servers [])
  (f))

(use-fixtures :each reset-servers-fixture)

(deftest test-start-server
  (let [srv (multiplexer/start-server "test-server"
                                      ["cat"]
                                      [:completion-provider])]

    (testing "start-server creates a server with correct structure"
      (is (= "test-server" (:name srv)))
      (is (some? (:proc srv)))
      (is (instance? ManyToManyChannel (:stdin srv)))
      (is (some #{:completion-provider} (:capabilities srv)))
      ;; :* is added automatically
      (is (some #{:*} (:capabilities srv))))

    (testing "start-server forwards output to server-output channel"
      (let [message {:jsonrpc "2.0" :id 1 :camelCase true}]
      ;; Send the message to cat's stdin
        (async/>!! (:stdin srv) message)
      ;; Wait a bit for the message to be echoed back and processed
        (Thread/sleep 200)
        (let [{body :message server :from} (async/poll! multiplexer/server-output)]
          (is (some? body))
          (is (= "test-server" server))
          (is (= 1 (:id body)))
          (is (:camel-case body)))))

        ;; Clean up
    (.destroy (:proc srv))
    (async/close! (:stdin srv))))

(deftest test-server-output-channel
  (testing "server-output is a valid channel"
    (is (some? multiplexer/server-output))
    (is (instance? ManyToManyChannel multiplexer/server-output))))

(deftest test-configured-capabilities-from-server-name
  (let [srv1 {:name "server1" :capabilities [:hover-provider :*] :is-default true}
        srv2 {:name "server2" :capabilities [:completion-provider :*] :is-default false}]
    (reset! multiplexer/enabled-servers [srv1 srv2])

    (testing "returns capabilities for non-default server"
      (is (= [:completion-provider :*] (multiplexer/configured-capabilities-from-server-name "server2"))))

    (testing "returns nil for default server"
      (is (nil? (multiplexer/configured-capabilities-from-server-name "server1"))))

    (testing "throws exception for non-existent server"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Server not found: nonexistent"
                            (multiplexer/configured-capabilities-from-server-name "nonexistent"))))))

(deftest test-is-default-server
  (let [srv1 {:name "server1" :capabilities [:hover-provider :*] :is-default true}
        srv2 {:name "server2" :capabilities [:completion-provider :*] :is-default false}]
    (reset! multiplexer/enabled-servers [srv1 srv2])

    (testing "returns true for default server"
      (is (true? (multiplexer/is-default-server "server1")))
      (is (false? (multiplexer/is-default-server "server2"))))

    (testing "throws exception for non-existent server"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Server not found: nonexistent"
                            (multiplexer/is-default-server "nonexistent"))))))

(deftest test-server-accept-method
  (let [srv1 {:name "server1" :capabilities [:hover-provider :*] :is-default true}
        srv2 {:name "server2" :capabilities [:completion-provider] :is-default false}
        srv3 {:name "server3" :capabilities [:definition-provider :type-definition-provider] :is-default false}]
    (reset! multiplexer/enabled-servers [srv1 srv2 srv3])

    (testing "returns default server and capable servers for supported methods"
      (let [completion-servers (multiplexer/server-accept-method "textDocument/completion")]
        (is (= 2 (count completion-servers)))
        (is (some #(= "server1" (:name %)) completion-servers))
        (is (some #(= "server2" (:name %)) completion-servers))
        (is (not (some #(= "server3" (:name %)) completion-servers))))

      (let [definition-servers (multiplexer/server-accept-method "textDocument/definition")]
        (is (= 2 (count definition-servers)))
        (is (some #(= "server1" (:name %)) definition-servers))
        (is (some #(= "server3" (:name %)) definition-servers))
        (is (not (some #(= "server2" (:name %)) definition-servers)))))

    (testing "returns only default server for unsupported methods"
      (let [format-servers (multiplexer/server-accept-method "textDocument/formatting")]
        (is (= 1 (count format-servers)))
        (is (= "server1" (:name (first format-servers))))))

    (testing "returns default server for any method in provider-methods"
      (let [rename-servers (multiplexer/server-accept-method "textDocument/rename")]
        (is (= 1 (count rename-servers)))
        (is (= "server1" (:name (first rename-servers))))))

    (testing "returns empty list for unknown methods not in provider-methods"
      (is (= 0 (count (multiplexer/server-accept-method "unknown/method")))))))
