(ns personal-rss-feed.admin.electric-app.components.basic-table
  (:require
   [clojure.string :as str]
   [hyperfiddle :as hf]
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]
   [hyperfiddle.electric-ui4 :as ui]))

(e/defn Table
  "Columns:
   
   [{::key        _
     ::title      \"str\"
     ?::renderer  (e/fn [row-data] (dom/...))}]
   
   Data can be a list of anything."
  [data-key columns data]
  (e/client
   (dom/table
    (dom/tbody
      (dom/tr (e/for [c columns] (dom/th (dom/text (::title c)))))
      (e/for-by data-key
                [d data]
                (dom/tr (e/for [c columns]
                          (let [Renderer (or (::renderer c)
                                             (e/fn [v]
                                               (e/client
                                                (dom/text
                                                 (str (v (::key
                                                          c)))))))]
                            (dom/td (new Renderer d))))))))))
