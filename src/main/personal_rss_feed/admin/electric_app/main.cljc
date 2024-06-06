(ns personal-rss-feed.admin.electric-app.main
  (:require
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-ui4 :as ui]
   [hyperfiddle.electric-dom2 :as dom]
   [personal-rss-feed.admin.electric-app.env :as e.env]
  ))

;; Saving this file will automatically recompile and update in your browser

(defonce client-state
  #?(:cljs
     (atom {::page nil})
     :clj nil))

(def pages
  {})

(e/defn Main
  [ring-request]
  (e/server
   (binding [e.env/ring-request ring-request]
     (e/client
      (binding [dom/node js/document.body]
        (let [{::keys [page]} (e/watch client-state)]
          (dom/div
            (dom/style {:margin "0.5em 3em"})
            (if page
              (ui/button (e/fn [] (swap! client-state assoc ::page nil))
                (dom/text "< Back"))
              (dom/h1
                (dom/text "RSS Server Main")))

            (if page
              (case page nil nil nil)
              (dom/ul
                (e/for [id (keys pages)]
                  (dom/li
                    (dom/a
                      (dom/props {:onclick #(swap! client-state assoc ::page id)})
                      (dom/text (name id))))))))))))))

