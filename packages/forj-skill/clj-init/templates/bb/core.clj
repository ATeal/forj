(ns {{namespace}}.core)

(defn greet
  "Return a greeting for the given name."
  [name]
  (str "Hello, " name "!"))

(defn -main
  "Application entry point."
  [& args]
  (println (greet (or (first args) "World"))))

(comment
  ;; REPL-driven development
  ;; Evaluate these forms to explore the code

  (greet "Claude")
  ;; => "Hello, Claude!"

  (-main)
  ;; Hello, World!

  (-main "Developer")
  ;; Hello, Developer!

  )
