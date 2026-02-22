(ns alligator.methods.initialize
  "Merge multiple server initialize responses"
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :refer [join]]))

(defmulti ^:private do-merge
  "Function that merge a capability key."
  (fn [key _capabilities] key))

(defmethod do-merge :default
  [key capabilities]
  ;; For most capabilities, merge according to LSP spec rules:
  ;; - If all are booleans, use OR logic (any true -> true)
  ;; - If any are objects, merge objects and combine with boolean logic
  ;; - For mixed boolean/object (union types), prefer object representation
  (let [non-nil-caps (filter some? capabilities)]
    (if (empty? non-nil-caps)
      {key nil}
      (let [booleans (filter boolean? non-nil-caps)
            objects (filter map? non-nil-caps)
            has-true-boolean (or (some true? booleans) false)]
        (cond
          ;; All are booleans - use OR logic
          (and (seq booleans) (empty? objects))
          {key has-true-boolean}

          ;; All are objects - deep merge
          (and (empty? booleans) (seq objects))
          {key (apply merge-with
                      (fn [v1 v2]
                        (cond
                          ;; Both are vectors/lists - concat and dedupe
                          (and (sequential? v1) (sequential? v2))
                          (vec (distinct (concat v1 v2)))
                          ;; Both are maps - recursive merge
                          (and (map? v1) (map? v2))
                          (merge v1 v2)
                          ;; Otherwise prefer non-nil, then v2
                          :else (or v2 v1)))
                      objects)}

          ;; Mixed boolean and objects - prefer object if any true boolean exists
          (and (seq objects) has-true-boolean)
          {key (apply merge-with
                      (fn [v1 v2]
                        (cond
                          (and (sequential? v1) (sequential? v2))
                          (vec (distinct (concat v1 v2)))
                          (and (map? v1) (map? v2))
                          (merge v1 v2)
                          :else (or v2 v1)))
                      objects)}

          ;; Mixed with false or no true boolean - use first object
          (seq objects)
          {key (first objects)}

          ;; Fallback
          :else
          {key (first non-nil-caps)})))))

(defn ^:private filter-capabilities-by-server-config
  "Filter capabilities based on server's configured capabilities.  If the server is
   default server, return all capabilities.  Otherwise, only return capabilities that
   match the server's configuration."
  [multiplexer capabilities server-name]
  (let [configured-caps (mux/configured-capabilities-from-server-name multiplexer server-name)
        is-default (mux/is-default-server multiplexer server-name)]
    (if is-default
      capabilities
      ;; Filter capabilities to only include configured ones
      (select-keys capabilities configured-caps))))

(defn merge-server-capabilities
  "Merge server capabilities from multiple initialize responses."
  [multiplexer & initialize-messages]
  (let [filtered-caps (->> initialize-messages
                           (map (fn [msg]
                                   (let [server-name (:from msg)
                                         caps (get-in msg [:message :result :capabilities])]
                                     (filter-capabilities-by-server-config multiplexer caps server-name))))
                           (filter some?))
        ;; Get all unique capability keys
        keys (->> filtered-caps
                  (mapcat keys)
                  distinct)]
    (->> keys
         (map (fn [k] (do-merge k (map k filtered-caps))))
         (into {}))))

(defmethod methods/process-server-message "initialize"
  [_ in-chan out-chan multiplexer]
  (let [servers (mux/list-servers multiplexer)
        num-servers (count servers)
        version (-> (io/resource "version.edn")
                    slurp
                    edn/read-string
                    :version)
        name (->> servers
                  (map :name)
                  (join "+")
                  (format "Alligator (%s)"))]
    (async/go-loop [responses []]
      (when-let [msg (async/<! in-chan)]
        ;; ;; update accept-command-list
        (when-let [accepted-commands (get-in msg [:message :result :capabilities :execute-command-provider :commands])]
          (mux/add-server-commands! multiplexer (:from msg) accepted-commands))

        ;; update response list
        (let [new-responses (conj responses msg)]
          (if (>= (count new-responses) num-servers)
            (async/>! out-chan
                      {:jsonrpc "2.0"
                       :id 1
                       :result {:capabilities (apply merge-server-capabilities multiplexer new-responses)
                                :server-info {:name name
                                              :version version}}})
            (recur new-responses)))))))
