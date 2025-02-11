(ns personal-rss-feed.admin.electric-app.main
  (:require [dustingetz.entity-browser0 :refer [EntityBrowser0]]
            [hyperfiddle.electric-dom3 :as dom] ;;  [hyperfiddle.electric-forms0 :as ef0]
            [hyperfiddle.electric3 :as e]
            [personal-rss-feed.admin.electric-app.context :as admin.context]))


(declare css)
#_
(e/defn ThreadDump3
  []
  (e/client (dom/style (dom/text css))
            (dom/props {:class "ThreadDump3"})
            (let [x (e/server nil)]
              #_(EntityBrowser0 x))))

(def css
  "
.ThreadDump3 > a + a { margin-left: .5em; }
.Browser.dustingetz-EasyTable { position: relative; } /* re-hack easy-table.css hack */")

(e/defn Main
  [server-config ring-request]
  (e/client
   (binding [dom/node                    js/document.body
             e/http-request              (e/server ring-request)
             admin.context/system-config (e/server server-config)]
     ; mandatory wrapper div https://github.com/hyperfiddle/electric/issues/74

     (dom/div (dom/props {:style {:display "contents"}})

       (dom/div (dom/text "hi"))



     ))))

(comment
  (require '[clojure.java.classpath :as cp])

  (->> (cp/classpath)
       (map str)
       (filter #(clojure.string/includes? % "electric")))
)