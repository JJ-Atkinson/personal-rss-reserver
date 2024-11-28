(ns personal-rss-feed.admin.electric-app.main
  (:require
   [tick.locale-en-us]
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-ui4 :as ui]
   [hyperfiddle.router :as router]
   [hyperfiddle.electric-dom2 :as dom]
   [personal-rss-feed.admin.electric-app.pages.queue-browser :as p.queue-browser]
   [personal-rss-feed.admin.electric-app.env :as e.env]
   [personal-rss-feed.admin.electric-app.pages.users :as pages.users]
   [personal-rss-feed.admin.electric-app.pages.queue-viewer :as pages.queue-viewer]
   [personal-rss-feed.admin.trial-electric-datafy.main :as e-datafy.main]
   [datagrid.gitbrowser]

   [hyperfiddle :as hf]))

;; Saving this file will automatically recompile and update in your browser

(defonce client-state
  #?(:cljs
     (atom {::page nil})
     :clj nil))

(def pages
  {::pages.users/UserViewPage           `pages.users/UserViewPage
   ::pages.queue-viewer/QueueViewerPage `pages.queue-viewer/QueueViewerPage})

(e/defn Entrypoint
  [f & args]
  (e/server
   (case f
     `datagrid.gitbrowser/GitBrowser (datagrid.gitbrowser/GitBrowser. ".")
     `p.queue-browser/QueueBrowser   (e/apply p.queue-browser/QueueBrowser args)
     `e-datafy.main/Main             (e-datafy.main/Main.)
     (e/client (dom/text "Not found" (cons f args))))))

(e/defn Main
  [config ring-request]
  (e/server
   (binding [e.env/ring-request ring-request
             e.env/config       config
             e/http-request     ring-request]
     (e/client
      (binding [dom/node js/document.body]
        (router/router (router/HTML5-History.)
                       (let [route (or (ffirst router/route)
                                       `(e-datafy.main/Main)
                                       `(p.queue-browser/QueueBrowser nil)
                                       `(datagrid.gitbrowser/GitBrowser)
                                       )]
                         (router/focus [route]
                                       (e/apply Entrypoint route))))
        #_
          (let [{::keys [page]} (e/watch client-state)]
            (dom/div
              (dom/style {:margin "0.5em 3em"})
              (if page
                (ui/button (e/fn [] (swap! client-state assoc ::page nil))
                  (dom/text "< Back"))
                (dom/h1
                  (dom/text "RSS Server Main")))

              (if page
                (case page
                  ::pages.users/UserViewPage           (pages.users/UserViewPage.)
                  ::pages.queue-viewer/QueueViewerPage (pages.queue-viewer/QueueViewerPage.)
                  nil)
                (dom/ul
                  (e/for [id (keys pages)]
                    (dom/li
                      (dom/a
                        (dom/props {:onclick #(swap! client-state assoc ::page id)})
                        (dom/text (name id))))))))))))))

