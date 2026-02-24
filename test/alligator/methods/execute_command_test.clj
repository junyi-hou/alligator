(ns alligator.methods.execute-command-test
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [taoensso.timbre :as timbre]))

(methods/load-handlers!)

(deftest test-execute-command-relay

  (testing "Relays command to the correct server"
    (let [in-chan (async/chan)
          out-chan (async/chan)
          m (mux/create-multiplexer out-chan)]

      (mux/add-server! m "s1" ["cat"])
      (mux/add-server! m "s2" ["cat"])
      (mux/add-server-commands! m "s1" ["command.one"])
      (mux/add-server-commands! m "s2" ["command.two"])

      (methods/process-client-message "workspace/executeCommand" in-chan m)

      (async/>!! in-chan {:jsonrpc "2.0"
                          :id 1
                          :method "workspace/executeCommand"
                          :params {:command "command.one"}})

      (let [relayed (async/<!! out-chan)]
        (is (= "s1" (:from relayed)))
        (is (= "command.one" (get-in relayed [:message :params :command])))
        (is (= 1 (get-in relayed [:message :id]))))
      (async/close! in-chan)
      (mux/stop-all-servers! m)))

  (testing "Returns error if no server supports the command"
    ;; with-redefs on the root log function to suppress warnings in async blocks
    (with-redefs [taoensso.timbre/-log! (fn [& _] nil)]
      (let [in-chan (async/chan)
            out-chan (async/chan)
            m (mux/create-multiplexer out-chan)]

        (mux/add-server! m "s1" ["cat"])
        (mux/add-server-commands! m "s1" ["command.one"])

        (methods/process-client-message "workspace/executeCommand" in-chan m)

        (async/>!! in-chan {:jsonrpc "2.0"
                            :id 2
                            :method "workspace/executeCommand"
                            :params {:command "unknown.command"}})

        (let [response (async/<!! out-chan)]
          (is (= 2 (:id response)))
          (is (contains? response :error))
          (is (= "Could not find the right server to relay the workspace/executeCommand request"
                 (get-in response [:error :message]))))
        (async/close! in-chan)
        (mux/stop-all-servers! m))))

  (testing "Returns error if multiple servers support the command"
    (with-redefs [taoensso.timbre/-log! (fn [& _] nil)]
      (let [in-chan (async/chan 10)
            out-chan (async/chan 10)
            m (mux/create-multiplexer out-chan)]

        (mux/add-server! m "s1" ["cat"])
        (mux/add-server! m "s2" ["cat"])
        (mux/add-server-commands! m "s1" ["shared.command"])
        (mux/add-server-commands! m "s2" ["shared.command"])

        (methods/process-client-message "workspace/executeCommand" in-chan m)

        (async/>!! in-chan {:jsonrpc "2.0"
                            :id 3
                            :method "workspace/executeCommand"
                            :params {:command "shared.command"}})

        (let [response (async/<!! out-chan)]
          (is (= 3 (:id response)))
          (is (contains? response :error))
          (is (= "Could not find the right server to relay the workspace/executeCommand request"
                 (get-in response [:error :message]))))
        (async/close! in-chan)
        (mux/stop-all-servers! m)))))
