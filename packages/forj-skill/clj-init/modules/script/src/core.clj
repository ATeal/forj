(ns {{project-name}}.core
  "{{project-name}} - A Babashka script.")

(defn -main
  [& args]
  (println "Hello from {{project-name}}!")
  (when (seq args)
    (println "Arguments:" args)))

(comment
  ;; REPL exploration
  (-main)
  (-main "test" "args")
  )
