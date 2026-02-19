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
