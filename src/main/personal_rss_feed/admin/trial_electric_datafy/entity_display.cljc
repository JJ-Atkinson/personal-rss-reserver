(ns personal-rss-feed.admin.trial-electric-datafy.entity-display
  (:require [hyperfiddle.electric :as e]
            [hyperfiddle.electric.impl.for :as e.for]
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.electric-ui4 :as ui]
            [taoensso.encore :as enc]
            [clojure.string :as string]
            [malli.core :as malli])
  #?(:clj
     (:import [java.lang Integer])))

(def EntityEditEnv
  [:map
   [::SubmitEdit! any?] ;; fn [[e a v]] -> true/false
   [::VerifyEdit! any?] ;; fn [[e a v]] -> validation messages tbd
  ]
)

(e/defn StringInput
  [value env]
  (e/server
   (e/client
    (ui/input (str value)
      (e/fn [x]
        (e/server (new (::SubmitEdit! env)
                       x)))
      (dom/props {:id (e/server (str (::input-id env)))})))))

(e/defn IntInput
  [value env]
  (e/server
   (e/client
    (ui/input (str value)
      (e/fn [x]
        (e/server (new (::SubmitEdit! env)
                       (Integer/parseInt x))))))))

(e/defn StringDisplay
  [value env]
  (e/server
   (e/client
    (dom/div (dom/span (dom/text (str value)))))))

(e/defn WrapInputForEntity
  [Input a]
  (e/server
   (assert Input)
   (e/fn [e env]
     (Input. (get e a)
             (assoc env
                    ::SubmitEdit!
                    (e/fn [v]
                      (new (::SubmitEdit! env)
                           [[e a v]])))))))


(e/defn LayoutEntityVertical
  [DisplayFns]
  (e/server
   (e/fn [e env]
     (e/client
      (dom/div
        (dom/style {:display        :flex
                    :flex-direction :column})
        (e/server
         (e/for [DisplayFn DisplayFns]
           (new DisplayFn e env))))))))

(e/defn LayoutEntityHorizontal
  [DisplayFns]
  (e/server
   (e/fn [e env]
     (e/client
      (dom/div
        (dom/style {:display        :flex
                    :flex-direction :row
                    :gap            "10px"})
        (e/server
         (e/for [DisplayFn DisplayFns]
           (new DisplayFn e env))))))))

(e/defn ListEntities
  [DisplayFn]
  (e/server
   (e/fn [es env]
     (e/client
      (dom/div
        (dom/style {:display        :flex
                    :flex-direction :column})
        (e/server
         (e/for [e es]
           (e/client
            (dom/div
              (dom/on "click"
                      (e/fn [_]
                        (e/server (new (::EntitySelected! env) e))))
              (e/server
               (new DisplayFn e env)))))))))))

(e/defn ListDetailEntities
  [ListFn DetailFn]
  (e/server
   (e/fn [es env]
     (e/server
      (let [!detail (atom nil)
            detail  (e/watch !detail)]
        (e/client
         (dom/div
           (dom/style {:display        :flex
                       :flex-direction :row})
           (e/server
            (new ListFn
                 es
                 (assoc env
                        ::EntitySelected!
                        (e/fn [e]
                          (e/server (reset! !detail e)))))
            (new DetailFn detail env)))))))))

(e/defn Pagination
  [DisplayFn opts]
  (e/server
   (e/fn [es env]
     (let [!page         (atom 0)
           page          (e/watch !page)
           ents-per-page (::ents-per-page opts 10)
           page-count    (int (Math/ceil (/ (count es) ents-per-page)))
           es            (take ents-per-page
                               (drop (* page ents-per-page) es))]
       (e/client
        (dom/div
          (dom/text (str "Page " page " of " page-count))
          (ui/button (e/fn [] (e/server (swap! !page dec))) (dom/text "<<"))
          (ui/button (e/fn [] (e/server (swap! !page inc))) (dom/text ">>"))
          (e/server
           (new DisplayFn es env))))))))

(e/defn Table
  [columns opts]
  (e/server
   (let [grid-template-columns (string/join " " (map #(::column-template % "1fr") columns))]
     (e/fn [es env]
       (e/client
        (dom/div
          (dom/style {:display               "grid"
                      :grid-template-columns grid-template-columns
                      :overflow              "scroll"
                      :width                 "300px"
                      :height                "400px"
                      :position              "relative"})
          (e/server
           (e/for [{::keys [title]} columns]
             (e/client
              (dom/div (dom/style {:border     "1px solid #888"
                                   :background "red"
                                   :position   "sticky"
                                   :top        "0"
                                  })
                (dom/text title))))

           (e/for [e es]
             (e/client
              (dom/div (dom/style {:display "contents"})
                (dom/on "click" (e/fn [_] (e/server (tap> ["Clicked!" e]))))
                (e/server
                 (e/for [{::keys [DisplayFn]} columns]
                   (e/client
                    (dom/div (dom/style {:border "1px solid #888"})
                      (e/server
                       (new DisplayFn e env))))))))))))))))

(e/def Schema->Viewer
  {'int?    StringDisplay
   'string? StringDisplay})

(e/def Schema->Editor
  {'int?    IntInput
   'string? StringInput})

(e/defn CreateViewerFor
  [schema]
  (e/server
   (let [{:keys [type keys properties]} (malli/ast schema)]
     (new LayoutEntityHorizontal
            (e/for [[key {:keys [order value properties]}] keys]
              (let [viewer (get Schema->Viewer (:type value))]
                (new WrapInputForEntity viewer key)))))))
