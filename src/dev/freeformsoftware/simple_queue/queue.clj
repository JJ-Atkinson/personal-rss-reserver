(ns dev.freeformsoftware.simple-queue.queue
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.data.priority-map :as priority-map]
   [clojure.string :as string]
   [dev.freeformsoftware.simple-queue.queue-item :as queue-item]))

;; The different q-s. 
;; ::waiting-q  
;; The waiting-q is where submitted items go first. It should always be sorted
;; ::active-q 
;; Anything pulled off the queue goes here until it either times out, errors, or succeeds.
;; ::dead-q 
;; The dead-q is a list of ::queue/failed and ::queue/succeeded items, trimmed down if required by core. The
;; typical usage is to give context to the rate-limit-fn. it is not guaranteed to be a complete list, though
;; it is sorted by ::queue/completion-time


(s/def ::name keyword?)
(s/def ::waiting-q (s/coll-of ::queue-item/id))
(s/def ::active-q (s/coll-of ::queue-item/id))      ;; 
(s/def ::dead-q (s/coll-of ::queue-item/id))
(s/def ::default-retry-limit number?)
(s/def ::rate-limit-fn ifn?)                                ;; (fn [lazy-recently-touched-queued-items] bool?)
(s/def ::timeout?-fn ifn?)
(s/def ::notify-timed-out! ifn?)

(s/def ::queue
  (s/keys :req [::name]
    ::opt [::waiting-q
           ::active-q
           ::dead-q
           ::rate-limit-fn
           ::default-retry-limit
           ::timeout?-fn
           ::notify-timed-out!]))

(defn- name->file-name
  [s]
  (-> (str s ".edn")
    (string/replace ":" "")
    (string/replace "/" "|")))

(defn- queue-def->file
  [persistence-dir queue-def-or-id]
  (io/file persistence-dir
    (cond-> queue-def-or-id
      (map? queue-def-or-id) (::name)
      true name->file-name)))


(defn write!
  [persistence-dir queue-def]
  (spit (queue-def->file persistence-dir queue-def)
    (pr-str (select-keys queue-def [::name ::active-q ::dead-q ::waiting-q]))))

(defn read!
  ([persistence-dir queue-id queue-def]
   (let [file (queue-def->file persistence-dir queue-id)]
     (merge
       queue-def
       (when (.exists file) (edn/read-string (slurp file)))))))
