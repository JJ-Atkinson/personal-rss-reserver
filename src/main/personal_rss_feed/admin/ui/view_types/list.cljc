(ns personal-rss-feed.admin.ui.view-types.list
  (:require
   contrib.str
   [hyperfiddle.electric :as e]
   [personal-rss-feed.admin.ui.view-types.view-options :as view-options]
   [hyperfiddle.electric-dom2 :as dom]
   [hyperfiddle.electric-ui4 :as ui]
   [personal-rss-feed.admin.ui.view-types.view-db :as view-db]
   ))

(e/defn DatifyListServer
  [{::view-options/keys [server-value id]}]
  (let [data       (map (fn [v]
                          {::view-options/id           (str (random-uuid))
                           ::view-options/server-value v})
                     server-value)]
    {::view-options/render-options {::view-options/id id
                                    ::items            (map (fn [i] (select-keys i [::view-options/id])) data)}
     ::view-options/further-render data}))

(e/defn DatifyTableClient 
  [{::view-options/keys [id]
    ::keys [data] :as input}]
  (let [id->view-fn (e/watch view-db/!id->view-fn)]
    (dom/div 
      (e/for [d data]
        (let [id (::view-options/id d)
              view-fn (get id->view-fn id)]
          (when view-fn 
            (new view-fn)))))))
