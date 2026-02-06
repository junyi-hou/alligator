(ns alligator.methods.execute-command-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.core.async :as async]
   [alligator.methods.execute-command :as exec-cmd]
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]))

(defn reset-states [f]
  (reset! mux/enabled-servers [])
  (reset! exec-cmd/server-commands-map {})
  (f))

(use-fixtures :each reset-states)

(methods/load-handlers!)

(deftest test-execute-command-relay
  (testing "Relays command to the correct server"
    (let [in-chan (async/chan 10)
          s1-stdin (async/chan 10)
          server1 {:name "s1" :stdin s1-stdin}
          server2 {:name "s2" :stdin (async/chan 10)}]

      (reset! mux/enabled-servers [server1 server2])
      (reset! exec-cmd/server-commands-map {"s1" ["command.one"]
                                            "s2" ["command.two"]})

      (methods/process-client-message "workspace/executeCommand" in-chan)

      (async/go
        (async/>! in-chan {:jsonrpc "2.0"
                           :id 1
                           :method "workspace/executeCommand"
                           :params {:command "command.one"}}))

      (let [relayed (async/<!! s1-stdin)]
        (is (= "command.one" (get-in relayed [:params :command])))
        (is (= 1 (:id relayed))))))

  (testing "Returns error if no server supports the command"
    (let [in-chan (async/chan 10)
          out-chan (async/chan 10)]

      (reset! exec-cmd/server-commands-map {"s1" ["command.one"]})

      (methods/process-client-message "workspace/executeCommand" in-chan)

      (async/go
        (async/>! in-chan {:jsonrpc "2.0"
                           :id 2
                           :method "workspace/executeCommand"
                           :params {:command "unknown.command"}}))

      (async/go
        (let [response (async/<! mux/server-output)]
          (async/>! out-chan response)))

      (let [response (async/<!! out-chan)]
        (is (= 2 (:id response)))
        (is (contains? response :error))
        (is (= "Could not find the right server to relay the workspace/executeCommand request"
               (get-in response [:error :message]))))))

  (testing "Returns error if multiple servers support the command"
    (let [in-chan (async/chan 10)
          out-chan (async/chan 10)]

      (reset! exec-cmd/server-commands-map {"s1" ["shared.command"]
                                            "s2" ["shared.command"]})

      (methods/process-client-message "workspace/executeCommand" in-chan)

      (async/go
        (async/>! in-chan {:jsonrpc "2.0"
                           :id 3
                           :method "workspace/executeCommand"
                           :params {:command "shared.command"}}))

      (async/go
        (let [response (async/<! mux/server-output)]
          (async/>! out-chan response)))

      (let [response (async/<!! out-chan)]
        (is (= 3 (:id response)))
        (is (contains? response :error))
        (is (= "Could not find the right server to relay the workspace/executeCommand request"
               (get-in response [:error :message])))))))
