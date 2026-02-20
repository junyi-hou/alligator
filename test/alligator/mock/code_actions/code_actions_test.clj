(ns alligator.mock.code-actions.code-actions-test
  (:require
   [alligator.methods :as methods]
   [alligator.mock.code-actions.server :as server]
   [alligator.test-utils :as utils]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [taoensso.timbre :as timbre]))

(use-fixtures :each utils/reset-all-states)

(methods/load-handlers!)

(timbre/merge-config! {:min-level :warn})

(def ^:private root-uri "file:///test")

(defmulti ^:private handle-server-requests (fn [_client request] (:method request)))

(defmethod handle-server-requests :default
  [_ request]
  (timbre/error  (str "[CLIENT] received unsupported request " (:method request))))

(defmethod handle-server-requests "workspace/applyEdit"
  [client request]
  (utils/respond client (:id request) {:applied true}))

(defn ^:private get-item-by-title [title actions]
  (some #(when (= title (:title %)) %) actions))

(deftest code-actions-integration-test
  (let [{:keys [client]} (utils/start-servers-and-client
                          ["--" "clj -M:test -m alligator.mock.code-actions.server clj" "--default"
                           "--" "clojure -M:test -m alligator.mock.code-actions.server clojure" "-c" "code-action-provider"]
                          handle-server-requests)]

    (testing "Handshake"
      (let [resp (utils/request client "initialize" {:capabilities {} :root-uri "file:///"})]
        (is (= "Alligator (clj+clojure)" (get-in resp [:result :server-info :name])))
        (utils/notify client "initialized")))

    (Thread/sleep 500)

    (let [resp (utils/request client "textDocument/codeAction"
                              {:textDocument {:uri (str root-uri "/file.clj")}
                               :range {:start {:line 0 :character 0}
                                       :end {:line 0 :character 0}}
                               :context {:diagnostics []}})
          actions (:result resp)]
      (testing "Getting all the code actions from both servers"
        (is (= (count actions) 4)))

      (testing "CodeAction with legacy command"
        (let [picked-action (get-item-by-title "command-from-clj" actions)
              resp (utils/request client "workspace/executeCommand" picked-action)]
          (is (= (:result resp) {:applied true}))))

      (testing "CodeAction with codeAction item"
        (let [picked-action (get-item-by-title "edit-from-clojure" actions)
              resp (utils/request client "codeAction/resolve" picked-action)
              result (:result resp)
              expected {:title (:title picked-action)
                        :kind (:kind picked-action)
                        :edit (server/edit-body "clojure")}]
          (is (= (:title expected) (:title result)))
          (is (= (:kind expected) (:kind result))))))

    (Thread/sleep 500)
    (utils/shutdown-client client)))
