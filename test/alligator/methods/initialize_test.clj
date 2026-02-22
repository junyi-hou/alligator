(ns alligator.methods.initialize-test
  (:require
   [alligator.methods :as methods]
   [alligator.methods.initialize :as init]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]))

(methods/load-handlers!)

(deftest test-process-server-message-initialize
  (testing "process-server-message 'initialize' merges responses from multiple servers"
    (let [in-chan (async/chan)
          out-chan (async/chan)
          m (mux/create-multiplexer)]

      (mux/add-server! m "s1" ["cat"])
      (mux/add-server! m "s2" ["cat"] [:hover-provider :code-action-provider])

      (methods/process-server-message "initialize" in-chan out-chan m)
      (async/>!! in-chan {:from "s1"
                          :message {:jsonrpc "2.0"
                                    :id 1
                                    :result {:capabilities {:completion-provider {:resolve-provider true}
                                                            :execute-command-provider {:commands ["move-to-let"]}
                                                            :code-action-provider {:code-action-kinds ["quickfix"]}}}}})
      (async/>!! in-chan {:from "s2"
                          :message {:jsonrpc "2.0"
                                    :id 1
                                    :result {:capabilities {:hover-provider true
                                                            :code-action-provider true}}}})

      (let [response (async/<!! out-chan)]
        (is (= 1 (:id response)))
        (is (= {:completion-provider {:resolve-provider true}
                :hover-provider true
                :code-action-provider {:code-action-kinds ["quickfix"]}
                :execute-command-provider {:commands ["move-to-let"]}}
               (get-in response [:result :capabilities])))
        (is (= "Alligator (s1+s2)" (get-in response [:result :server-info :name])))
        ;; check execute-command is registered
        (is (= (get @(:server-commands-map m) "s1") ["move-to-let"])))
      (mux/stop-all-servers! m))))

(deftest test-merge-initialize-response
  (testing "Merge booleans using OR logic"
    (let [cap-values [true false]
          expected {:completion-provider true}]
      (is (= expected
             (#'init/do-merge :completion-provider cap-values)))))

  (testing "Works with missing keys"
    (let [cap-values [true nil]
          expected {:completion-provider true}]
      (is (= expected
             (#'init/do-merge :completion-provider cap-values)))))

  (testing "Merge nested maps by combining keys"
    (let [cap-values [{:open-close true} {:change 1}]
          expected {:text-document-sync {:open-close true :change 1}}]
      (is (= expected
             (#'init/do-merge :text-document-sync cap-values)))))

  (testing "Maps take precedence over boolean"
    (let [cap-values [true {:code-action-kinds ["quickfix"]}]
          expected {:code-action-provider {:code-action-kinds ["quickfix"]}}]
      (is (= expected
             (#'init/do-merge :code-action-provider cap-values)))))

  (testing "concat vectors"
    (let [cap-values [{:code-action-kinds ["refactor"]} {:code-action-kinds ["quickfix"]}]
          expected {:code-action-provider {:code-action-kinds ["refactor" "quickfix"]}}]
      (is (= expected
             (#'init/do-merge :code-action-provider cap-values))))))
