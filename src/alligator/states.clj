(ns alligator.states
  (:require
   [clojure.core.async :as async]))

;; keep track of the id <> method of all client->server requests
(defonce outstanding-client-requests (atom {}))

;; We need to manipulate the id if server sends a request to the client.  This is
;; because we dispatch client message to server selectively, so that servers may be on
;; different id schedule. (e.g., one server is on id=100 and another on id=50). In this
;; case, if the second sever sends a request to the client, it will be id=51. However,
;; this id has already been used by the client, and thus collision happens.

;; To avoid collusion, we need to use a different id, and maintain a mapping between the
;; original id and the modified id.
(defonce server-request-id-mapping (atom {}))
;; channel to signal Alligator to exit
(defonce exit-chan (async/chan))

(def alligator-cli-options
  [["-c" "--config CONFIG_FILE"
    "Path to the config.yaml file. Will be ignore if config is provided after --"
    :default "config.toml"]
   [nil "--debug" "Whether to print debug output to stderr"
    :id :debug
    :default false]
   ["-h" "--help"]])

(def server-options
  [["-Ac" "--alligator.capabilities CAPABILITIES" "A list of capabilities to use for the server"
    :multi true
    :default []
    :id :capabilities
    :update-fn (fnil conj [])]
   ["-Ad" "--alligator.default" "Whether to set this server as the default server"
    :default false
    :id :default
    :parse-fn boolean]])
