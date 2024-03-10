(ns personal-rss-feed.admin.ui-2.viewer-system)







;; things that need to be handled:
;; 1. arbitrary data in
;; 2. arbitrary random programatic updates of that data
;; 3. ui driven updates of the data
;; 4. linking additional data - essentially requiring the whole system to be recursive.

;; idea 1
;; Ingest data, deconstruct it into the system, have an (update new-data change-metadata) fn

(defn view-data [data {:keys [on-changed] :as default-view-options}])
(defn update-view [new-data change-metadata])
(defn update-value [context new-value])

;; example

(view-data
  {:person/id     1
   :person/name   "Jarrett"
   :person/parent 1}
  {:on-change (fn [x] (#_make-diff-and-commit))})

;;=> 
