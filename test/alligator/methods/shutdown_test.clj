(ns alligator.methods.shutdown-test
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]))

(methods/load-handlers!)

(deftest test-process-server-shutdown
  (testing "Should only send one shutdown message from multiple servers"

    (let [in-chan (async/chan)
          out-chan (async/chan)
          m (mux/create-multiplexer)
          msg1 {:from "s1" :message {:jsonrpc "2.0" :id 1000 :result nil}}
          msg2 {:from "s2" :message {:jsonrpc "2.0" :id 1000 :result nil}}]
      (mux/add-server! m "s1" ["cat"] [] false)
      (mux/add-server! m "s2" ["cat"] [] false)
      (methods/process-server-message "shutdown" in-chan out-chan m)

      (async/>!! in-chan msg1)
      ;; should timeout as we should not put any thing in out-chan yet
      (let [timeout-ch (async/timeout 500)
            [_ ch] (async/alts!! [out-chan timeout-ch])]
        (is (= ch timeout-ch) "Should not have anything from the output-channel"))

      (async/>!! in-chan msg2)
      (let [msg (async/<!! out-chan)]
        (is (= (:id msg) 1000))
        (is (= (:result msg) nil)))
      (mux/stop-all-servers! m))))

(deftest test-shutdown-alligator
  (testing "shutdown message also shuts down alligator"
    (let [in-chan (async/chan)
          out-chan (async/chan)
          m (mux/create-multiplexer)
          msg {:from "s1" :message {:jsonrpc "2.0" :id 2000 :result nil}}]
      (mux/add-server! m "s1" ["cat"] {} false)
      (methods/process-server-message "shutdown" in-chan out-chan m)

      ;; when no shutdown message, exit-chan is alive and empty
      (let [exit-chan (get-in m [:states :exit-chan])
            timeout (async/timeout 1000)
            [_ ch] (async/alts!! [exit-chan timeout])]
        (is (= ch timeout)))

      ;; send shutdown message
      (async/>!! in-chan msg)
      (is (= (:id (async/<!! out-chan)) 2000))

      ;; after shutdown message is sent, exit-chan is closed and return nil immediately
      (let [exit-chan (get-in m [:states :exit-chan])
            timeout (async/timeout 1000)
            [_ ch] (async/alts!! [exit-chan timeout])]
        (is (= ch exit-chan)))
      (mux/stop-all-servers! m))))
