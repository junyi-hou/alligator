(ns alligator.log)

(defn log
  "Simple logging to stderr with a label."
  [label msg]
  (binding [*out* *err*]
    (println (format "[%s] %s" label msg))
    (flush)))
