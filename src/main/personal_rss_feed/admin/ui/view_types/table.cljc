(ns personal-rss-feed.admin.ui.view-types.table
  (:require
   contrib.str
   [hyperfiddle.electric :as e]
   [personal-rss-feed.admin.ui.view-types.view-options :as view-options]
   [hyperfiddle.electric-dom2 :as dom]
   [hyperfiddle.electric-ui4 :as ui]
   [personal-rss-feed.admin.ui.view-types.view-db :as view-db]
   ))


;; Works on lists of maps, or a map itself.
(e/defn DatifyTableServer
  [{::view-options/keys [server-value id]}]
  (let [table-type :map-only
        data       (map (fn [[k v]]
                          [{::view-options/id           (str (random-uuid))
                            ::view-options/server-value k}
                           {::view-options/id           (str (random-uuid))
                            ::view-options/server-value v}])
                     server-value)]
    (case table-type
      :map-only
      {::view-options/render-options {::view-options/id id
                                      ::rows            (map (fn [row] (map #(select-keys % [::view-options/id]) row)) data)}
       ::view-options/further-render (apply concat data)})))

(e/defn DatifyTableClient
  [{::view-options/keys [id]
    ::keys              [rows] :as input}]
  (let [id->view-fn (e/watch view-db/!id->view-fn)]
    (js/console.log "DatifyTableClient" rows id->view-fn)
    (dom/table
      (dom/thead
        (dom/tr
          (dom/th (dom/text "Key"))
          (dom/th (dom/text "Value"))))
      (dom/tbody
        (e/for [row rows]
          (dom/tr
            (e/for [col row]
              (dom/td
                (let [id      (::view-options/id col)
                      view-fn (get id->view-fn id)]
                  (js/console.log "view-fn" view-fn)
                  (when view-fn
                    (new view-fn)))))))))))
