(ns personal-rss-feed.admin.electric-app.pages.queue-browser
  (:require
   [clojure.string :as str]
   [datagrid.datafy-renderer :as dat.renderer]
   [datagrid.file-explorer :as dg.file-explorer]
   [datagrid.schema :as schema]
   [datagrid.ui :as ui]
   [dev.freeformsoftware.simple-queue.core
    #?(:clj :as
       :cljs :as-alias) simple-queue]
   [dev.freeformsoftware.simple-queue.queue-item
    #?(:clj :as
       :cljs :as-alias) queue-item]
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-css :as css]
   [hyperfiddle.electric-dom2 :as dom]
   [hyperfiddle.router :as router]
   [malli.registry]
   [personal-rss-feed.admin.electric-app.env :as e.env]
   [personal-rss-feed.ingest.lotus-eaters.download-file :as-alias le.download-file]
   [personal-rss-feed.ingest.lotus-eaters.extract-audio :as-alias le.extract-audio]
   [personal-rss-feed.ingest.lotus-eaters.fetch-metadata :as-alias le.fetch-metadata]
   [hyperfiddle.electric-ui4 :as ui4]))

(e/def current-queue)

(defonce !popup-text
  #?(:cljs (atom nil)
     :clj nil))

(def warning-series
  {:else "#ff5252"
   0     "transparent"
   1     "#ffeded"
   2     "#ffdbdb"
   3     "#ff8080"
   4     "#ff6161"})

(def queues
  [{::name ::le.download-file/download-queue}
   {::name ::le.fetch-metadata/fetch-metadata-queue}
   {::name ::le.extract-audio/extract-audio-queue}])

#?(:clj
   (defn get-queue-items
     [queue queue-name {::keys [focus-mode search-str] :as filters}]
     (let [all? (nil? focus-mode)]
       (->>
        (concat
         (when (or all? (= focus-mode :waiting)) (simple-queue/qview-dead queue queue-name))
         (when (or all? (= focus-mode :active)) (simple-queue/qview-dead queue queue-name))
         (when (or all? (= focus-mode :dead)) (simple-queue/qview-dead queue queue-name)))))))

(def queue-item-schema-registry
  {::queue-item/id              :uuid
   ::queue-item/submission-time inst?
   ::queue-item/activation-time inst?
   ::queue-item/completion-time inst?
   ::queue-item/retry-count     int?
   ::queue-item/priority        int?
   ::queue-item/status          keyword?})

(e/defn RenderQueueName
  [props e a V]
  (e/server
   (let [v (V.)]
     (e/client
      (router/link [:queue v]
                   (dom/text (name v)))))))

(e/defn RenderShortSha
  [props e a V]
  (e/server
   (let [v (V.)]
     (e/client
      (router/link [:id v]
                   (dom/text (subs (str v) 0 8)))))))

(e/defn RenderStatus
  [props e a V]
  (e/server
   (let [v (V.)]
     (e/client
      (dom/style {:backgroundColor (case v
                                     ::queue-item/waiting        "transparent"
                                     ::queue-item/activated      "#aefcfa"
                                     ::queue-item/error-retrying "#f68fff"
                                     ::queue-item/failed         "#ff576a"
                                     ::queue-item/succeeded      "#cffad5")})
      (dom/text (name v))))))

#?(:cljs
   (defn date->str
     [^js d]
     (.toLocaleString d "en-US" #js {"timezone" "US/Central"})))

(e/defn RenderFormattedTime
  [props e a V]
  (e/server
   (let [v (V.)]
     (e/client
      (dom/text (or (some-> v
                            date->str)
                    "--"))))))

(e/defn RenderRetryCount
  [p e a V]
  (e/server
   (let [v (V.)]
     (e/client
      (dom/style {:backgroundColor (get warning-series
                                        v
                                        (get warning-series :else))})
      (dom/text v)))))

(e/defn RenderStr
  [p e a V]
  (e/server
   (let [v (V.)]
     (e/client
      (dom/text (str v))))))

(defn kw->str
  [kw]
  (str
   (if-let [ns (namespace kw)]
     (str (get {"dev.freeformsoftware.simple-queue.queue-item"         "::queue-item/"
                "dev.freeformsoftware.simple-queue.core"               "::simple-queue/"
                "personal-rss-feed.ingest.lotus-eaters.download-file"  "::le.download-file/"
                "personal-rss-feed.ingest.lotus-eaters.extract-audio"  "::le.extract-audio/"
                "personal-rss-feed.ingest.lotus-eaters.fetch-metadata" "::le.fetch-metadata/"}
               ns
               (str ":" ns "/")))
     ":")
   (name kw)))

(e/defn RenderFormKeyOverride
  [props e a V]
  (e/server
   (let [v (V.)]
     (e/client
      (dom/code
       (cond (keyword? v)    (dom/text (kw->str v))
             (sequential? v) (dom/text (str \[ (str/join " " (map kw->str v)) \]))))))))

(e/defn MultiLineStrRenderer
  [s]
  (e/client
   (let [[preview & rest] (str/split-lines s)]
     (if rest
       (ui4/button (e/fn [] (reset! !popup-text s))
         (dom/text preview))
       (dom/code
        (dom/text preview))))))

(e/defn RenderFormValue
  [props e a V]
  (e/server
   (let [v (V.)]
     (e/client
      (if (string? v)
        (MultiLineStrRenderer. v)
        (dom/code
         (dom/text
          (cond (keyword? v) (kw->str v)
                :else        (pr-str v)))))))))

(e/def queue-item-renderers
  {::queue-item/id              RenderShortSha
   ::queue-item/submission-time RenderFormattedTime
   ::queue-item/activation-time RenderFormattedTime
   ::queue-item/completion-time RenderFormattedTime
   ::queue-item/retry-count     RenderRetryCount
   ::queue-item/priority        RenderStr
   ::queue-item/status          RenderStatus
   ::dat.renderer/key           RenderFormKeyOverride
   ::dat.renderer/value         RenderFormValue})


(e/defn ListQueues
  [_queues]
  (e/client
   (dom/div (dom/props {:class (css/scoped-style
                                (css/rule {:overflow :auto})
                                (css/rule "a[disabled=true]"
                                          {:cursor :text :color :initial :text-decoration :none}))})
     (e/server
      (binding [dat.renderer/Render          dat.renderer/SchemaRenderer
                dat.renderer/schema-registry (schema/registry {::name :keyword})
                dat.renderer/renderers       (assoc dat.renderer/renderers ::name RenderQueueName)]

        (dat.renderer/RenderGrid.
         {::dat.renderer/row-height-px 25
          ::dat.renderer/max-height-px "100%"
          ::dat.renderer/columns       [{::dat.renderer/attribute ::name}]}
         nil
         nil
         (e/fn [] queues)))))))

(e/defn ListQueueItems
  [queue-name]
  (e/client
   (do
     (dg.file-explorer/RouterInput. {::dom/type        :search
                                     ::dom/placeholder "Search URL or SHA"
                                     ::dom/style       {:grid-area "search"}}
                                    :search-term)
     (dom/div
       (dom/props {:style {:max-height "100%"
                           :overflow   :auto
                           :position   :relative
                           :grid-area  "log"}})

       (e/server
        (binding [dat.renderer/Render          dat.renderer/SchemaRenderer
                  dat.renderer/schema-registry (schema/registry queue-item-schema-registry)
                  dat.renderer/renderers       (merge dat.renderer/renderers queue-item-renderers)]
          (dat.renderer/RenderGrid.
           {::dat.renderer/row-height-px 25
            ::dat.renderer/max-height-px "100%"
            ::dat.renderer/columns       [{::dat.renderer/attribute ::queue-item/id}
                                          {::dat.renderer/attribute ::queue-item/status}
                                          {::dat.renderer/attribute ::queue-item/retry-count
                                           ::dat.renderer/sortable  true}
                                          {::dat.renderer/attribute ::queue-item/submission-time
                                           ::dat.renderer/sortable  true}
                                          {::dat.renderer/attribute ::queue-item/activation-time
                                           ::dat.renderer/sortable  true}
                                          {::dat.renderer/attribute ::queue-item/completion-time
                                           ::dat.renderer/sortable  true}
                                          {::dat.renderer/attribute ::queue-item/priority
                                           ::dat.renderer/sortable  true}]}
           nil
           nil
           (e/fn []
             (e/server (get-queue-items
                        (:queue e.env/config)
                        queue-name
                        {}))))))))))

(defn flatten-queue-item
  [qi]
  (let [paths (mapcat (fn [partial-path]
                        (map (fn [x] [partial-path x]) (keys (get qi partial-path))))
               [::queue-item/data
                ::queue-item/completion-data])]
    [(reduce (fn [acc path]
               (assoc acc path (get-in qi path)))
             qi
             paths)
     paths]))

(e/defn QueueItemInfo
  [queue-item]
  (e/client
   (dom/div (dom/props {:style {:grid-row 1 :grid-column "1 / 3"}})
     (e/server
      (let [[queue-item paths] (flatten-queue-item queue-item)
            rendered-keys      (concat [::queue-item/id
                                        ::queue-item/status
                                        ::queue-item/retry-count
                                        ::queue-item/submission-time
                                        ::queue-item/activation-time
                                        ::queue-item/completion-time
                                        ::queue-item/priority]
                                       paths)]
        (binding [dat.renderer/Render          dat.renderer/SchemaRenderer
                  dat.renderer/schema-registry (schema/registry queue-item-schema-registry)
                  dat.renderer/renderers       (merge dat.renderer/renderers queue-item-renderers)]
          (dat.renderer/RenderForm. {::dat.renderer/row-height-px 25
                                     ::dat.renderer/max-height-px (* 25 (inc (count rendered-keys)))
                                     ::dat.renderer/keys          rendered-keys}
                                    nil
                                    nil
                                    (e/fn* [] queue-item))))))))


(e/defn QueueItemInfoPanel
  [queue-item-id]
  (e/server
   (let [qi (simple-queue/resolve!i (:queue e.env/config) queue-item-id)]
     (e/client
      (dom/div
        (dom/props {:style {:border-top            "2px lightgray solid"
                            :overflow              :auto
                            :position              :relative
                            :height                :auto
                            :padding-bottom        "2rem"
                            :display               :grid
                            :grid-template-columns "auto 1fr"
                            :grid-area             "details"}})

        (ui/ClosePanelButton. ['.. `(QueueBrowser ~current-queue)])
        (QueueItemInfo. qi)
      )))))

(e/defn TextPopup
  []
  (e/client
   (let [text (e/watch !popup-text)]
     (when text
       (dom/div
         (dom/props {:class "popup-root"})
         (dom/div
           (dom/props {:class "popup-body"})
           (ui4/button (e/fn [] (reset! !popup-text nil))
             (dom/text "X"))
           (dom/pre
            (dom/props {:class "popup-content"})
            (dom/code
             (dom/text text)))))))))

(e/defn QueueBrowser
  [default-queue]
  (e/client
   (dom/props {:style {:padding        "1rem"
                       :padding-bottom "0.5rem"
                       :margin         0
                       :box-sizing     :border-box
                       :overflow       :hidden
                       :height         "100dvh"}})
   (dom/div (dom/props {:class (ui/LayoutStyle. (contains? router/route :id))})
     (let [queue (or (some-> router/route
                             :queue
                             ffirst)
                     default-queue
                     ::le.download-file/download-queue)]
       (binding [current-queue queue]
         (ListQueues. nil)
         (ListQueueItems. queue)
         (when-let [queue-item-id (:id router/route)]
           (QueueItemInfoPanel. (ffirst queue-item-id)))
         (TextPopup.))))))
