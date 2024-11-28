(ns personal-rss-feed.admin.trial-electric-datafy.main
  (:require
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric.impl.for :as e.for]
   [hyperfiddle.electric-ui4 :as ui]
   [hyperfiddle.electric-dom2 :as dom]
   [personal-rss-feed.admin.electric-app.env :as e.env]
   [personal-rss-feed.admin.trial-electric-datafy.entity-display :as edisp]
   [malli.provider :as mp]
   [malli.core :as malli]
  ))


(comment
  (mp/provide
   [{:id   1
     :tags #{:a :b :c}}
    {:id   2
     :tags #{:a :c}}
    {:id   4
     :tags #{:a :c}}])
  (malli/properties [:map {:thing :a} [:a {:k :j} int?] [:b float?]])
  (malli/ast [:map {:thing :a} [:a {:k :j} int?] [:b float?]])
  (malli/type (second (first (malli/entries
                              [:map {:thing :a} [:a {:a :b} int?] [:b float?]]))))

  (malli/type (first (malli/children (second (first (malli/entries [:map [:a int?]]))))))
  (malli/explain
   [:map [:a int?] [:b float?]]
   {:a 1.1 :b 2})
)

(defonce ents-atom
  (atom {:a 1
         :b 2
         :c 3}))
(comment
  (reset! ents-atom [{:a 1
                      :b 2
                      :c 3}
                     {:a 10
                      :b 20
                      :c 30}
                     {:a 100
                      :b 200
                      :c 300}])

  (reset! ents-atom (vec (take 10000
                               (repeatedly (fn []
                                             {:a (rand-int 10000)
                                              :b (rand-int 90)
                                              :c (rand-int 3999)})))))

  (tap> ents-atom))

(e/def Editor
  (edisp/ListEntities.
   (edisp/LayoutEntityHorizontal.
    [(edisp/WrapInputForEntity. edisp/StringDisplay :a)
     (edisp/WrapInputForEntity. edisp/StringInput :b)
     (edisp/WrapInputForEntity. edisp/IntInput :c)])))

(e/def ListDetailFn
  (edisp/ListDetailEntities.
   (edisp/ListEntities.
    (edisp/LayoutEntityHorizontal.
     [(edisp/WrapInputForEntity. edisp/StringDisplay :a)
      (edisp/WrapInputForEntity. edisp/StringInput :c)]))
   (edisp/LayoutEntityVertical.
    [(edisp/WrapInputForEntity. edisp/IntInput :a)
     (edisp/WrapInputForEntity. edisp/IntInput :b)
     (edisp/WrapInputForEntity. edisp/IntInput :c)])))

(e/def TableFn
  (edisp/Pagination.
   (edisp/Table.
    [{::edisp/column-template "1fr"
      ::edisp/title           "A"
      ::edisp/DisplayFn       (edisp/WrapInputForEntity. edisp/StringDisplay :a)}
     {::edisp/column-template "2fr"
      ::edisp/title           "B"
      ::edisp/DisplayFn       (edisp/WrapInputForEntity. edisp/StringInput :b)}
     {::edisp/column-template "1fr"
      ::edisp/title           "C"
      ::edisp/DisplayFn       (edisp/WrapInputForEntity. edisp/StringDisplay :c)}]
    {})
   {::edisp/ents-per-page 88}))

(e/defn Main
  []
  (e/server
   (let [ent (e/watch ents-atom)]
     (e/client
      (dom/div
        (dom/text "hello datafy")
        (e/server
         (tap> (edisp/CreateViewerFor.
                [:map [:a int?] [:b int?] [:c int?]]))

         (let [Viewer (edisp/CreateViewerFor.
                       [:map [:a int?] [:b int?] [:c int?]])]
           (tap> Viewer)
           (new Viewer {:a 1 :b 2 :c 333} {}))
         (new #_ListDetailFn
              TableFn
              ent
              {::edisp/SubmitEdit!
               (e/fn [eavs]
                 (e/server
                  (swap! ents-atom #(reduce
                                     (fn [ents [og-ent a v]]
                                       (mapv (fn [ent]
                                               (cond-> ent
                                                 (= (:a ent) (:a og-ent)) (assoc a v)))
                                             ents))
                                     %
                                     eavs))))})))))))
