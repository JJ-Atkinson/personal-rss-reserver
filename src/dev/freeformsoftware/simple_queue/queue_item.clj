(ns dev.freeformsoftware.simple-queue.queue-item
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [com.fulcrologic.guardrails.core :refer [>defn => ?]]
   [clojure.spec.alpha :as s]))

(s/def ::id uuid?)
(s/def ::submission-time inst?)
(s/def ::activation-time inst?)
(s/def ::completion-time inst?)
(s/def ::completion-data map?)
(s/def ::queue keyword?)                                    ;; same as ::queue/name
(s/def ::data map?)
(s/def ::retry-limit number?)
(s/def ::priority number?)
(s/def ::status #{::waiting ::activated ::error-retrying ::failed ::succeeded})
(s/def ::retry-count int?)                                  ;; the number of times an item has been retried

(s/def ::item
  (s/keys :req [::id ::submission-time ::queue ::status ::retry-count]
    :opt [::priority ::errors ::activation-time ::completion-time ::retry-limit]))

(defn- queue-item->file
  [persistence-dir id]
  (io/file persistence-dir (str id ".edn")))

(>defn read!
  [persistence-dir id]
  [string? uuid? => ::item]
  (let [file (queue-item->file persistence-dir id)]
    (when (.exists file)
      (try
        (edn/read-string (slurp file))
        (catch Exception e
          {::id id 
           ::reader-exception e
           ::raw-file-contents (try (slurp file)
                                    (catch Exception e
                                      "----- Can't read file."))})))))

(>defn write!
  [persistence-dir item]
  [string? ::item => ::item]
  (spit (queue-item->file persistence-dir (::id item)) (pr-str item))
  item)
