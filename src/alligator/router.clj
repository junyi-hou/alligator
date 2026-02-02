(ns alligator.router
  "Message routing system for the alligator LSP multiplexer.

  This namespace handles the flow of LSP messages between clients and language servers
  through a topic-based pub/sub system using core.async channels.

  Key functionality:

  - Topic-based dispatch: Messages are categorized by method names and published to
    specific topics for handler subscription

  - Client message routing: Reads raw LSP messages from input, determines message type,
    and dispatches to appropriate handler methods.
    - For client requests, also update the map that records the method associated with
      each request id, this helps server message router to determine the message method.
    - Route client responses to a special topic, :response, where id manipulations is
      necessary to avoid id collision. See `methods/client_respond.clj` for more information.

  - Server message routing: Processes server responses and forwards them to the correct
    client handler.

  The router maintains request-response correlations and ensures messages reach
  their intended handlers in the multiplexed environment."
  (:require [clojure.core.async :as async]
            [jsonrpc4clj.coercer :as coercer]
            [alligator.multiplexer :as mux]
            [alligator.request-states
             :refer [outstanding-client-requests server-request-id-mapping]]
            [alligator.methods :as methods]))

(derive ::response.error ::response)
(derive ::response.result ::response)

;; client -> server message 

(defn ^:private get-client-message-topic
  "Get method for client messages.

   Returns the method name if a specialized handler exists, otherwise :generic.
   Returns :error if receives an error message."
  [message]
  (let [msg-type (coercer/input-message-type message)
        implemented-methods-or-default #(or (some (fn [[k _]] (when (= k %) k)) (clojure.core/methods methods/process-client-message)) :default)]
    ;; update inflight-requests
    (when (= msg-type :request)
      (let [{:keys [id method]} message]
        (swap! @outstanding-client-requests assoc id method)))

    (case (coercer/input-message-type message)
      :notification (:method message)
      :request (implemented-methods-or-default (:method message))
      ;; special handling - when client sends a response
      ;; we just need to route it to the server who sends
      ;; the request.
      ;; The server is identified in `server-request-id-mapping`
      :response.result :response
      :response.error :error
      :illegal-client-message-type)))

(defn start-dispatching-client-messages!
  "Automatically register and start handlers for all methods defined in
  `methods/process-client-message`.

   For each registered method, it:
   1. Creates a dedicated channel.
   2. Subscribes the channel to `client-message-publisher`.
   3. Calls the handler implementation to start its processing loop."
  [input-chan]
  (let [client-message-publisher (async/pub input-chan get-client-message-topic)
        all-handlers (clojure.core/methods methods/process-client-message)]
    (doseq [[method-name _] all-handlers]
      (let [msg-chan (async/chan 10)]
        (async/sub client-message-publisher method-name msg-chan)
        (methods/process-client-message method-name msg-chan)))))

;; server -> client message 

(defn ^:private uniquify-server-request-id
  "Manipulate the request id when a server sends a request to the client.
   When a server sends a request to the client (via alligator), we need to:
   1. Replace the server's request ID with a unique ID (negative to avoid collision).
   2. Store the mapping so we can route the response back to the correct server.
   3. Return the modified message to send to the client"
  [{:keys [message] server-name :from}]
  (let [original-id (:id message)
        new-id (str (random-uuid))]  ; Use negative IDs
    ;; Store the mapping
    (swap! server-request-id-mapping assoc new-id {:server-name server-name
                                                   :original-id original-id})
    ;; Return message with new ID
    {:message (assoc message :id new-id) :from server-name}))

(defn ^:private get-server-message-topic
  "Get method for server messages.

   Returns the method name if a specialized handler exists, otherwise :generic.
   Returns :error if receives an error message."
  [{:keys [message]}]
  (let [msg-type (coercer/input-message-type message)
        implemented-methods-or-default #(or (some (fn [[k _]] (when (= k %) k)) (clojure.core/methods methods/process-client-message)) :default)]

    (case msg-type
      :notification (:method message)
      :request (implemented-methods-or-default (:method message))
      :response.result (if-let [method (get @outstanding-client-requests (:id message))]
                         (implemented-methods-or-default method)
                         ;; if a server respond with id that does not exist
                         :illegal-server-message-type)
      :response.error :error
      :illegal-server-message-type)))

(defn start-processing-server-messages!
  "Automatically register and start handlers for all methods defined in
   `alligator.methods/process-server-message`.
   For each registered method, it:
   1. Manipulate the id and store the mapping if the incoming server message is a
   request.
   2. Creates a dedicated channel.
   3. Subscribes the channel to the server output channel.
   3. Calls the handler implementation to start its processing loop."
  [output-chan]
  (let [server-output-after-handle-request-id (async/chan 100)
        server-msg-publisher (async/pub server-output-after-handle-request-id get-server-message-topic)
        all-handlers (clojure.core/methods methods/process-server-message)]

    ;; first uniqufy server request id
    (async/go-loop []
      (when-let [original-msg (async/<! mux/server-output)]
        (if (= (coercer/input-message-type (:message original-msg)) :request)
          (async/>! server-output-after-handle-request-id (uniquify-server-request-id original-msg))
          (async/>! server-output-after-handle-request-id original-msg))
        (recur)))

    (doseq [[method-name _] all-handlers]
      (let [msg-chan (async/chan 10)]
        (async/sub server-msg-publisher method-name msg-chan)
        (methods/process-server-message method-name msg-chan output-chan)))))
