(ns alligator.methods.diagnostics-test
  (:require
   [alligator.methods :as methods]
   [alligator.methods.diagnostics :as diag]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]))

(methods/load-handlers!)

(deftest test-process-server-diagnostics
  (testing "Merges diagnostics from different servers"
    (let [uri "file:///test.clj"
          d1 {:range {:start {:line 0 :character 0} :end {:line 0 :character 5}} :message "Err 1"}
          d2 {:range {:start {:line 1 :character 0} :end {:line 1 :character 5}} :message "Err 2"}
          m1 {:from "s1" :message {:params {:uri uri :version 1 :diagnostics [d1]}}}
          m2 {:from "s2" :message {:params {:uri uri :version 1 :diagnostics [d2]}}}]

      (#'diag/process-server-diagnostics m1)
      (let [result (#'diag/process-server-diagnostics m2)]
        (is (= uri (:uri (:params result))))
        (is (= 2 (count (:diagnostics (:params result)))))
        (is (some #(= "Err 1" (:message %)) (:diagnostics (:params result))))
        (is (some #(= "Err 2" (:message %)) (:diagnostics (:params result)))))))

  (testing "Respects versions - newer version overrides"
    (let [uri "file:///version.clj"
          d1 {:message "Old"}
          d2 {:message "New"}
          m1 {:from "s1" :message {:params {:uri uri :version 1 :diagnostics [d1]}}}
          m2 {:from "s1" :message {:params {:uri uri :version 2 :diagnostics [d2]}}}]

      (#'diag/process-server-diagnostics m1)
      (#'diag/process-server-diagnostics m2)
      (is (= 2 (get-in @diag/diagnostics-cache [uri "s1" :version])))
      (is (= ["New"] (map :message (get-in @diag/diagnostics-cache [uri "s1" :diagnostics]))))))

  (testing "Ignores older versions"
    (let [uri "file:///version-old.clj"
          d1 {:message "Old"}
          d2 {:message "New"}
          m1 {:from "s1" :message {:params {:uri uri :version 2 :diagnostics [d2]}}}
          m2 {:from "s1" :message {:params {:uri uri :version 1 :diagnostics [d1]}}}]

      (#'diag/process-server-diagnostics m1)
      (#'diag/process-server-diagnostics m2)
      (is (= 2 (get-in @diag/diagnostics-cache [uri "s1" :version])))
      (is (= ["New"] (map :message (get-in @diag/diagnostics-cache [uri "s1" :diagnostics])))))))

(deftest test-process-server-message-diagnostic
  (testing "process-server-message 'textDocument/publishDiagnostics' merges and sends to output-chan"
    (let [m (mux/create-multiplexer)
          in-chan (async/chan 10)
          out-chan (async/chan 10)
          uri "file:///test.clj"]

      (methods/process-server-message "textDocument/publishDiagnostics" in-chan out-chan m)

      (async/>!! in-chan {:from "s1"
                          :message {:jsonrpc "2.0"
                                    :method "textDocument/publishDiagnostics"
                                    :params {:uri uri :version 1 :diagnostics [{:message "Err 1"}]}}})

      ;; First message should come out
      (let [resp1 (async/<!! out-chan)]
        (is (= "Err 1" (get-in resp1 [:params :diagnostics 0 :message]))))

      (async/>!! in-chan {:from "s2"
                          :message {:jsonrpc "2.0"
                                    :method "textDocument/publishDiagnostics"
                                    :params {:uri uri :version 1 :diagnostics [{:message "Err 2"}]}}})

      ;; Second message should contain merged diagnostics
      (let [resp2 (async/<!! out-chan)]
        (is (= 2 (count (get-in resp2 [:params :diagnostics]))))
        (is (some #(= "Err 1" (:message %)) (get-in resp2 [:params :diagnostics])))
        (is (some #(= "Err 2" (:message %)) (get-in resp2 [:params :diagnostics]))))
      (mux/stop-all-servers! m))))

(deftest test-process-client-message-did-close
  (testing "textDocument/didClose notification deletes diagnostic-cache entry"
    (let [m (mux/create-multiplexer)
          uri "file:///closed.clj"
          in-chan (async/chan 10)]

      ;; Pre-populate cache
      (reset! diag/diagnostics-cache {uri {"s1" {:version 1 :diagnostics []}}})
      (is (contains? @diag/diagnostics-cache uri))

      (methods/process-client-message "textDocument/didClose" in-chan m)

      (async/>!! in-chan {:jsonrpc "2.0"
                          :method "textDocument/didClose"
                          :params {:textDocument {:uri uri}}})
      ;; Need a small timeout or wait for the side effect as process-client-message
      ;; usually runs a go-loop that might take a tiny bit of time
      (Thread/sleep 10)
      (is (not (contains? @diag/diagnostics-cache uri)))
      (mux/stop-all-servers! m))))
