(ns personal-rss-feed.admin.ui.recursive-viewer
  (:require
   contrib.str
   [personal-rss-feed.admin.ui.view-types.table :as view-table]
   [personal-rss-feed.admin.ui.view-types.list :as view-list]
   [personal-rss-feed.admin.ui.view-types.view-options :as view-options]
   [personal-rss-feed.admin.ui.view-types.pr-str :as pr-str]
   [personal-rss-feed.admin.ui.view-types.view-db :as view-db]
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]
   [hyperfiddle.electric-ui4 :as ui]))

(e/def recursive-data-viewer-binding)                       ;; should always be bound to EntrypointDataViewer


(defn viewer-type-for
  [x]
  (cond
    (map? x)        ::table
    ;;(keyword? x) ::keyword
    ;;(symbol? x) ::symbol
    ;;(string? x) ::string
    (sequential? x) ::list
    ;;(number? x) ::number
    :else           ::pr-str
  ))



(e/defn DatifyServer
  [{::view-options/keys [server-value viewer-type] :as input}]
  (e/server
   (case viewer-type
     ::table   (view-table/DatifyTableServer. input)
     ::keyword nil
     ::symbol  nil
     ::string  nil
     ::list    (view-list/DatifyListServer. input)
     ::number  nil
     ::pr-str  (pr-str/DatifyPrStrServer. input))))

(e/defn ClientRender
  [{::view-options/keys [viewer-type] :as render-options}]
  ;;#_
  (cond
    (= viewer-type ::table)  (e/client (view-table/DatifyTableClient. render-options))
    (= viewer-type ::list)   (e/client (view-list/DatifyTableClient. render-options))
    (= viewer-type ::pr-str) (e/client (pr-str/DatifyPrStrClient. render-options))
    :else                    nil)
  #_(case viewer-type
      ::table  (e/client (view-table/DatifyTableClient. render-options))
      ;;::keyword nil ::symbol nil ::string nil ::list nil ::number nil
      ::pr-str (e/client (pr-str/DatifyPrStrClient. render-options))
      ""))


(e/defn RecursiveDataViewer
  [{::view-options/keys [server-value id]}]
  (e/server
   (let [type           (viewer-type-for server-value)

         {::view-options/keys [render-options further-render] :as e}
         (DatifyServer. {::view-options/server-value server-value
                         ::view-options/viewer-type  type
                         ::view-options/id           id})

         render-options (assoc render-options
                               ::view-options/viewer-type
                               type)]

     (e/client
      (do
        (swap! view-db/!id->view-fn assoc
          id
          (e/fn []
            (js/console.log "F called!" render-options)
            (ClientRender. render-options)))
        nil))

     (println server-value type e)
     #_(ClientRender. (assoc render-options
                             ::view-options/viewer-type
                             type))
     #_(e/client
        (pr-str/DatifyPrStrClient. render-options))

     (e/for [further-render further-render]
       (println "further-render" further-render)
       (new recursive-data-viewer-binding further-render)))))

(e/defn DataViewerEntrypoint
  [d]
  (let [id (str (random-uuid))]
    (RecursiveDataViewer. {::view-options/server-value d
                           ::view-options/id           id})
    (e/client
     (let [id->view-fn (e/watch view-db/!id->view-fn)]
       (js/console.log id->view-fn id)
       (when-let [f (get id->view-fn id)]
         (js/console.log "F exists!")
         (new f))
       nil))))

(def test-data
  #_{:ten     {:eleven 12 :thirteen 14}
     :fifteen {:sixteen 17 :eighteen 19}}
  (list 1
        2
        "three"
        [:four 'five :six/seven 'eight/nine]
        {:ten     {:eleven 12 :thirteen 14}
         :fifteen {:sixteen 17 :eighteen 19}}))

(e/defn Viewer
  []
  (e/server
   (binding [recursive-data-viewer-binding RecursiveDataViewer]
     (DataViewerEntrypoint. test-data))))
