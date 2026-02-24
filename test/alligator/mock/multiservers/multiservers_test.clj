(ns alligator.mock.multiservers.multiservers-test
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [alligator.test-utils :as utils]
   [clojure.test :refer [deftest is testing]]))

(methods/load-handlers!)

(deftest ^:mock multi-server-integration-test
  (let [{:keys [client] :as test-objects}
        (utils/start-servers-and-client
         ["--" "clj -M:test -m alligator.mock.basic.server" "--default"
          "--" "clojure -M:test -m alligator.mock.basic.server" "-c" "completion-provider"])]

    (testing "Handshake"
      (let [resp (utils/request client "initialize" {:capabilities {} :root-uri "file:///"})]
        (is (= "Alligator (clj+clojure)" (get-in resp [:result :server-info :name])))
        (utils/notify client "initialized")))

    (utils/stop-servers-and-client! test-objects)))
