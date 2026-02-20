(ns alligator.mock.multiservers.multiservers-test
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [alligator.test-utils :as utils]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each utils/reset-all-states)

(methods/load-handlers!)

(deftest multi-server-integration-test
  (let [{:keys [client]} (utils/start-servers-and-client
                          ["--" "clj -M:test -m alligator.mock.basic.server" "--default"
                           ;; alligator identifies servers using their executable, and two servers with the same name
                           ;; will name collision
                           "--" "clojure -M:test -m alligator.mock.basic.server" "-c" "completion-provider"])]

    (testing "successfully launch 2 servers"
      (is (= (count @mux/enabled-servers) 2)))

    (testing "Handshake"
      (let [resp (utils/request client "initialize" {:capabilities {} :root-uri "file:///"})]
        (is (= "Alligator (clj+clojure)" (get-in resp [:result :server-info :name])))
        (utils/notify client "initialized")))

    (utils/shutdown-client client)))
