(ns personal-rss-feed.admin.electric-app.main
  (:require
   [clojure.math :refer [floor-div]]
   [hyperfiddle.electric3 :as e]
   [hyperfiddle.electric-dom3 :as dom]
  ;;  [hyperfiddle.electric-forms0 :as ef0]
   [missionary.core :as m]
   [personal-rss-feed.admin.electric-app.context :as admin.context]
  ))


#_
(e/defn DemoInputCircuit-controlled
  []
  (let [!s (atom "")
        s  (e/watch !s)]
    (reset! !s (ef0/Input s))
    (reset! !s (ef0/Input s))
    (dom/code (dom/text (pr-str s)))))

(e/defn Main
  [server-config ring-request]
  (e/client
   (binding [dom/node       js/document.body
             e/http-request (e/server ring-request)]
     ; mandatory wrapper div https://github.com/hyperfiddle/electric/issues/74

     (dom/div (dom/props {:style {:display "contents"}})
     #_
     (DemoInputCircuit-controlled)))))
