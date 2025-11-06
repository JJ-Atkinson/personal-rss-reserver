(ns personal-rss-feed.admin.electric-app.main
  (:require [dustingetz.entity-browser0 :refer [EntityBrowser0]]
            [hyperfiddle.electric-dom3 :as dom] ;;  [hyperfiddle.electric-forms0 :as ef0]
            [hyperfiddle.router4 :as hf.router4]

            [clojure.datafy :as dfy]
            [clojure.core.protocols :as cc.proto]
            [hyperfiddle.electric3 :as e]
            [personal-rss-feed.admin.electric-app.context :as admin.context]
            #?@(:clj [[dev.freeformsoftware.simple-queue.core :as simple-queue]
                      [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
                      [dev.freeformsoftware.simple-queue.queue :as queue]
            [personal-rss-feed.ingest.lotus-eaters.shared :as le.shared]

                     ]
                :cljs [[dev.freeformsoftware.simple-queue.core :as-alias simple-queue]
                       [dev.freeformsoftware.simple-queue.queue-item :as-alias queue-item]
                       [dev.freeformsoftware.simple-queue.queue :as-alias queue]
                       [personal-rss-feed.ingest.lotus-eaters.shared :as-alias le.shared]])))

(defn nav-server-config
  [sc k v]
  )

(defn datafy-server-config
  [sc]

  #?(:clj (let [queue       (get-in sc [:lotus-eaters-ingest ::le.shared/queue])
                queue-names (keys (::simple-queue/name->queue @queue))]
            (mapv (fn [qn]
                    (with-meta {:name qn}
                               {`cc.proto/nav (constantly {:found-me! qn})}))
                  queue-names)))
)

(defn add-dfy
  [server-config]
  (with-meta server-config
             {`cc.proto/datafy datafy-server-config
             }))

(declare css)
(e/defn AdminDashboard
  []
  (e/client (dom/style (dom/text css))
            (dom/props {:class "ThreadDump3"})
            (let [x (e/server (datafy-server-config admin.context/system-config))]
              (EntityBrowser0 x))))

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

       (hf.router4/router
        (hf.router4/HTML5-History)
        (dom/div
          (dom/div (dom/text "hi"))
          (AdminDashboard)))


     ))))

(comment
  (require '[clojure.java.classpath :as cp])

  (->> (cp/classpath)
       (map str)
       (filter #(clojure.string/includes? % "electric")))
)