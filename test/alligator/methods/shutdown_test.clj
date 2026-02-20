(ns alligator.methods.shutdown-test
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [alligator.states :as states]
   [alligator.test-utils :refer [reset-all-states]]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each reset-all-states)

(methods/load-handlers!)

(deftest test-process-server-shutdown
  (testing "Should only send one shutdown message from multiple servers"

    (let [in-chan (async/chan)
          out-chan (async/chan)
          msg1 {:from "s1" :message {:jsonrpc "2.0" :id 1000 :result nil}}
          msg2 {:from "s2" :message {:jsonrpc "2.0" :id 1000 :result nil}}]
      (reset! mux/enabled-servers [{:name "s1"} {:name "s2"}])
      (methods/process-server-message "shutdown" in-chan out-chan)

      (async/>!! in-chan msg1)
      ;; should timeout as we should not put any thing in out-chan yet
      (let [timeout-ch (async/timeout 500)
            [_ ch] (async/alts!! [out-chan timeout-ch])]
        (is (= ch timeout-ch) "Should not have anything from the output-channel"))

      (async/>!! in-chan msg2)
      (let [msg (async/<!! out-chan)]
        (is (= (:id msg) 1000))
        (is (= (:result msg) nil))))))

(deftest test-shutdown-alligator
  (testing "shutdown message also shuts down alligator"
    (let [in-chan (async/chan)
          out-chan (async/chan)
          msg {:from "s1" :message {:jsonrpc "2.0" :id 2000 :result nil}}]
      (reset! mux/enabled-servers [{:name "s1"}])
      (methods/process-server-message "shutdown" in-chan out-chan)

      ;; when no shutdown message, mux/exit-chan is alive and empty
      (let [timeout (async/timeout 1000)
            [_ ch] (async/alts!! [states/exit-chan timeout])]
        (is (= ch timeout)))

      ;; send shutdown message
      (async/>!! in-chan msg)
      (is (= (:id (async/<!! out-chan)) 2000))

      ;; after shutdown message is sent, mux/exit-chan is closed and return nil immediately
      (let [timeout (async/timeout 1000)
            [_ ch] (async/alts!! [states/exit-chan timeout])]
        (is (= ch states/exit-chan))))))
