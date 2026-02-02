(ns alligator.methods
  "Method handler management system for alligator LSP multiplexer.

  This namespace provides a dynamic loading and dispatching system for LSP method handlers:

  - Multi-methods for processing server and client messages: process-server-message, process-client-message
  - Dynamic discovery and loading of handler modules from the alligator.methods directory
  - Plugin-style architecture where each .clj file can register its own method handlers

  Handler modules should implement specific LSP protocol methods by defining
  implementations for the multi-methods. The load-handlers! function automatically
  discovers and loads all handler modules at startup.

  Example handler implementation:
    (defmethod process-server-message \"textDocument/hover\"
      [method msg-chan output-chan]
      ;; Handle hover requests
      )"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defmulti process-server-message
  "Processes server-side LSP messages using method-based dispatch.

  This multi-method handles server-originated messages. Different type of messages,
  defined by the method of the message, can be handled by specific implementation of
  this multi-method. Method-specific handlers read client message from the
  message-channel (you can assume that the channel contains only message of that
  specific method), process it and write to the Alligator stdout channel, which will be
  directed to che client."
  (fn [method _msg-chan _output-chan] method))

(defmulti process-client-message
  "Processes client-side LSP messages using method-based dispatch.

  This multi-method handles client-originated messages. Different type of messages,
  defined by the method of the message, can be handled by specific implementation of
  this multi-method. Method-specific handlers read client message from the
  message-channel (you can assume that the channel contains only message of that
  specific method), process it and write to stdin of the server(s) that are suppose to
  receive it."
  (fn [method _msg-chan] method))

(defn ^:private ns-from-file [file]
  (let [path (.getPath file)
        ;; Convert path to namespace (e.g., .../alligator/methods/diagnostics.clj -> alligator.methods.diagnostics)
        ns-str (-> path
                   (str/replace #".*src/" "")
                   (str/replace #"\.clj$" "")
                   (str/replace #"/" "."))]
    (symbol ns-str)))

(defn load-handlers!
  "Dynamically load all namespaces in the alligator.methods directory."
  []
  (let [method-dir (io/file "src/alligator/methods")]
    (doseq [file (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj"))
                         (file-seq method-dir))]
      (let [ns-name (ns-from-file file)]
        (require ns-name)))))
