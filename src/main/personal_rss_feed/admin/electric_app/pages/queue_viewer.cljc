(ns personal-rss-feed.admin.electric-app.pages.queue-viewer
  (:require
   [dev.freeformsoftware.simple-queue.queue
    #?(:clj :as
       :cljs :as-alias) queue]
   [dev.freeformsoftware.simple-queue.queue-item
    #?(:clj :as
       :cljs :as-alias) queue-item]
   [dev.freeformsoftware.simple-queue.core
    #?(:clj :as
       :cljs :as-alias) simple-queue]
   #?(:clj [personal-rss-feed.admin.electric-app.components.zprint :as zp])
   [clojure.string :as str]
   [hyperfiddle :as hf]
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]
   [hyperfiddle.electric-ui4 :as ui]
   [tick.core :as tick]
   [personal-rss-feed.admin.electric-app.env :as e.env]
   [personal-rss-feed.ingest.lotus-eaters.download-file :as-alias le.download-file]
   [personal-rss-feed.ingest.lotus-eaters.fetch-metadata :as-alias le.fetch-metadata]
   [personal-rss-feed.ingest.lotus-eaters.extract-audio :as-alias le.extract-audio]
   [personal-rss-feed.admin.electric-app.components.basic-table :as basic-table]))

(defonce !client-state
  #?(:cljs (atom {::focused-queue      nil
                  ::focused-queue-item nil
                  ::hover-time         -1})
     :clj nil))

(e/defn QueueItemViewer
  [])

(def warning-series
  {:else "#ff5252"
   0     "transparent"
   1     "#ffeded"
   2     "#ffdbdb"
   3     "#ff8080"
   4     "#ff6161"})

#?(:cljs
   (defn date->str
     [^js d]
     (.toLocaleString d "en-US" #js {"timezone" "US/Central"})))


(e/defn FormattedTimeRenderer
  [key m]
  (e/client
   (dom/text (date->str (get m key)))))

(e/defn RetryCountRenderer
  [m]
  (e/client
   (let [v (get m ::queue-item/retry-count)]
     (dom/style {:backgroundColor (get warning-series
                                       v
                                       (get warning-series :else))})
     (dom/text v))))

(e/defn StatusRenderer
  [m]
  (e/client
   (let [v (get m ::queue-item/status)]
     (dom/style {:backgroundColor (case v
                                    ::queue-item/waiting        "transparent"
                                    ::queue-item/activated      "#aefcfa"
                                    ::queue-item/error-retrying "#f68fff"
                                    ::queue-item/failed         "#ff576a"
                                    ::queue-item/succeeded      "#cffad5"
                                  )})
     (dom/text (name v)))))

#?(:cljs (defn random-int!
           []
           (js/Math.floor (* (Math/random) 10000000))))

(e/defn HoverFormatted
  [key m]
  (e/client
   (let [v                     (get m key)
         !view                 (atom {:state       :hidden
                                      ::hover-time nil})
         view                  (e/watch !view)
         {::keys [hover-time]} (e/watch !client-state)
         TransitionF           (e/fn [transition-map _]
                                 (let [now (random-int!)]
                                   (swap! !view (fn [x]
                                                  (-> x
                                                      (update :state (fn [ces] (get transition-map ces ces)))
                                                      (assoc ::hover-time now))))
                                   (swap! !client-state assoc ::hover-time now)))]
     (dom/props {:class "hover-anchor"})
     (dom/on "mouseenter" (e/partial 2 TransitionF {:hidden :hover}))
     (dom/on "mouseleave" (e/partial 2 TransitionF {:hover :hidden}))
     (dom/on "mousedown"
             (e/partial 2
                        TransitionF
                        {:locked-open :hidden
                         :hover       :locked-open
                         :hidden      :locked-open}))
     (if (not (or (= :locked-open (:state view))
                  (and (= :hover (:state view))
                       (= (::hover-time view) hover-time))))
       (dom/span (dom/text "Hover to see"))
       (dom/div
         (dom/on "mouseleave" (e/partial 2 TransitionF {:hover :hidden}))
         (dom/props {:class "hover-result"})
         (dom/pre (dom/code (dom/text (e/server (zp/pprint-str v))))))))))

(e/def columns
  [{::basic-table/key   ::queue-item/id
    ::basic-table/title "ID"}
   {::basic-table/key      ::queue-item/submission-time
    ::basic-table/title    "Submission Time"
    ::basic-table/renderer (e/partial 2 FormattedTimeRenderer ::queue-item/submission-time)}
   {::basic-table/key      ::queue-item/retry-count
    ::basic-table/title    "Retry Count"
    ::basic-table/renderer RetryCountRenderer}
   {::basic-table/key      ::queue-item/status
    ::basic-table/title    "Status"
    ::basic-table/renderer StatusRenderer}
   {::basic-table/key      ::queue-item/activation-time
    ::basic-table/title    "Activation Time"
    ::basic-table/renderer (e/partial 2 FormattedTimeRenderer ::queue-item/activation-time)}
   {::basic-table/key      ::queue-item/completion-time
    ::basic-table/title    "Completion Time"
    ::basic-table/renderer (e/partial 2 FormattedTimeRenderer ::queue-item/completion-time)}
   {::basic-table/key   ::queue-item/priority
    ::basic-table/title "Priority"}])


#?(:clj
   (defn get-queue-items
     [queue queue-name {::keys [focus-mode search-str] :as filters}]
     (let [all? (nil? focus-mode)]
       (->>
        (concat
         (when (or all? (= focus-mode :waiting)) (simple-queue/qview-dead queue queue-name))
         (when (or all? (= focus-mode :active)) (simple-queue/qview-dead queue queue-name))
         (when (or all? (= focus-mode :dead)) (simple-queue/qview-dead queue queue-name)))
        (take 400)))))


(e/defn ViewWholeQueue
  []
  (e/client
   (let [!search-str (atom "")
         search-str  (e/watch !search-str)]
     (dom/props {:class "entity-navigator"})
     (basic-table/Table. ::queue-item/id
                         columns
                         (e/server (get-queue-items (:queue e.env/config)
                                                    ::le.download-file/download-queue
                                                    ;;    ::le.fetch-metadata/fetch-metadata-queue
                                                    ;; ::le.extract-audio/extract-audio-queue
                                                    {})))
   )))



(e/defn QueueViewerPage
  []
  (e/client
   (dom/div
     (dom/h2 (dom/text "Queue Viewer"))
     (ViewWholeQueue.))))