(ns personal-rss-feed.feed.feed-host
  (:require
   [personal-rss-feed.feed.db :as db]
   [ring.adapter.jetty9 :as ring-jetty]
   [clojure.data.xml :as xml]
   [clj-simple-router.core :as router]
   [ring.util.response :as response]
   [personal-rss-feed.name-utils :as name-utils]
   [hiccup.core :as hiccup]
   [integrant.core :as ig]
   [taoensso.timbre :as log])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Date Locale)))

(defn format-time [^Date t]
  (when t
    (.format (DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss ZZ" Locale/ENGLISH)
      (.atOffset (.toInstant t) ZoneOffset/UTC))))

(defn generate-description
  [{:episode/keys [excerpt id uuid url]}]
  (hiccup/html
    [:div
     [:div excerpt] [:br]
     [:a {:href url} "Lotus eaters website link"]]))

(defn write-podcast-feed
  [config 
   podcast
   episodes]
  (xml/element :rss {:version        "2.0"
                     "xmlns:atom"    "http://www.w3.org/2005/Atom/"
                     "xmlns:rdf"     "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     "xmlns:itunes"  "http://www.itunes.com/dtds/podcast-1.0.dtd"
                     "xmlns:content" "http://purl.org/rss/1.0/modules/content/"}
    (apply
      xml/element :channel {}
      (xml/element :title {} (:podcast/title podcast))
      (xml/element :pubDate {} (-> episodes last :episode/publish-date format-time))
      (xml/element :description {} (:podcast/description podcast))
      (xml/element :language {} "en-US")
      (xml/element "itunes:author" {} "Lotus Eaters")
      (xml/element "itunes:image" {} (:podcast/icon-uri podcast))

      (->> episodes
        (map (fn [episode]
               (xml/element :item {}
                 (xml/element :title {} (:episode/title episode))
                 (xml/element :description {} (:episode/excerpt episode))
                 (xml/element :enclosure {:url    (name-utils/format-audio (:s3/public-s3-prefix config) (:episode/uuid episode))
                                          :length (:episode/audio-content-length episode)
                                          :type   "audio/mp3"})
                 (xml/element :pubDate {} (-> episode :episode/publish-date format-time))
                 (xml/element :guid {} (-> episode :episode/id str))
                 (xml/element "content:encoded" {}
                   (xml/cdata (generate-description episode)))
                 (xml/element "itunes:image" {:href "http://relayfm.s3.amazonaws.com/uploads/broadcast/image/17/cortex_artwork.png"}))))))))

(defn routes
  [{:keys [db/conn feed/secret-path-segment feed/public-feed-address] :as config}]
  {"GET /" secret-path-segment "/feeds"
   (fn [{}]
     (let [podcasts (db/known-podcasts conn)]
       (response/response
         (hiccup/html
           [:body
            [:ul
             (map (fn [{:podcast/keys [title feed-uri description id]}]
                    [:li
                     [:strong title] [:br]
                     [:i feed-uri] [:br]
                     [:span description] [:br]
                     [:strong "ID: " id] [:br]
                     [:span (str public-feed-address secret-path-segment "/" id)]])
               podcasts)]]))))

   (str "GET /" secret-path-segment "/feed/*")
   (fn [{[id] :path-params}]
     (when-let [podcast (db/podcast-by-id conn id)]
       (let [episodes (filter :episode/audio-content-length
                        (db/podcast-feed conn (:podcast/feed-uri podcast)))]
         (-> (response/response
               (xml/emit-str (write-podcast-feed config podcast episodes)))
           (response/content-type "application/rss+xml")))))})



;;; SERVER COMPONENT ==============================

;; The indirection for `handler` and `init-key` enable tools.ns.refresh, without the need for suspend/resume. quite handy
(defn handler
  [config]
  (let [router (memoize router/router)]
    (fn [req]
      (try
        (or ((router (#'routes config)) req)
          (response/not-found "Route not found"))
        (catch Exception e (log/error "Handler error" e))))))

(defmethod ig/init-key ::server
  [_ {:keys [jetty] :as config}]
  (let [options (assoc jetty :join? false)]
    (println "Starting server" options)
    (ring-jetty/run-jetty (handler config) options)))

(defmethod ig/suspend-key! ::server
  [_ _])

(defmethod ig/resume-key ::server
  [_ _ _ _])

(defmethod ig/halt-key! ::server
  [_ server]
  (ring-jetty/stop-server server))
