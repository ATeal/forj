(ns forj.lisa.analytics
  "Analytics for Lisa Loop iterations - tool usage and REPL compliance."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn extract-tool-calls
  "Parse a stream-json log file and extract tool calls.
   Returns a seq of {:name <tool-name> :input <tool-input>} maps.

   The log file is JSONL format where each line is a JSON object.
   Tool calls appear in 'assistant' type messages with content
   containing 'tool_use' objects."
  [log-file]
  (when (and (fs/exists? log-file)
             (pos? (fs/size log-file)))
    (let [lines (-> log-file slurp (str/split #"\n"))]
      (->> lines
           (filter seq)
           (mapcat (fn [line]
                     (try
                       (let [entry (json/parse-string line true)]
                         (when (= "assistant" (:type entry))
                           (let [content (get-in entry [:message :content])]
                             (->> content
                                  (filter #(= "tool_use" (:type %)))
                                  (map (fn [tool-use]
                                         {:name (:name tool-use)
                                          :input (:input tool-use)}))))))
                       (catch Exception _
                         nil))))
           (remove nil?)))))

(comment
  ;; Test extract-tool-calls
  (extract-tool-calls ".forj/logs/lisa/parallel-error-wrapper-2.json")
  (extract-tool-calls ".forj/logs/lisa/parallel-extract-tool-calls-1.json")

  ;; Count tool calls
  (count (extract-tool-calls ".forj/logs/lisa/parallel-extract-tool-calls-1.json"))

  ;; Group by tool name
  (->> (extract-tool-calls ".forj/logs/lisa/parallel-extract-tool-calls-1.json")
       (group-by :name)
       (map (fn [[k v]] [k (count v)]))
       (into {}))
  )
