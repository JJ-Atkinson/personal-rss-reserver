(ns personal-rss-feed.feed.feed-host
  (:require
   [ring.adapter.jetty9 :as ring-jetty]
   [clojure.data.xml :as xml]
   [clj-simple-router.core :as router]
   [ring.util.request :as request]
   [ring.util.response :as response]
   [integrant.core :as ig]
   [clj-rss.core :as rss]
   [taoensso.timbre :as log])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Date Locale)))

(defn format-time [^Date t]
  (when t
    (.format (DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss ZZ" Locale/ENGLISH)
      (.atOffset (.toInstant t) ZoneOffset/UTC))))

(defn download-uuid->link 
  [])

(defn write-podcast-feed
  [podcast
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
                 (xml/element :enclosure {:url    (:episode/origin-uri episode)
                                          :length (:episode/audio-content-length episode)
                                          :type   "audio/mp3"})
                 (xml/element :pubDate {} (-> episode :episode/publish-date format-time))
                 (xml/element :guid {} (-> episode :episode/saved-audio-uuid str))
                 (xml/element "content:encoded" {}
                   (xml/cdata (:episode/excerpt episode)))
                 (xml/element "itunes:image" {:href "http://relayfm.s3.amazonaws.com/uploads/broadcast/image/17/cortex_artwork.png"}))))))))



(def routes
  {"GET /feed/*"
   (fn [{[feed-id] :path-params}]
     (->
       (response/response (xml/emit-str (write-podcast-feed
                                          #:podcast{:title       "category/brokenomics",
                                                    :icon-uri    "https://www.lotuseaters.com/logo.svg",
                                                    :updated-at  #inst"2023-11-07T21:10:22.000-00:00",
                                                    :description "Latest category_brokenomics posts from The Lotus Eaters"}
                                          [#:episode{:title                "Thomas Sowell",
                                                     :thumbnail-origin-uri "https://images.ctfassets.net/khed0tvttjco/10Mh8WilP4C8Un72tKaKwX/5ce4c0064451ea60addf2ebf0234b06f/18.Sowell_-_Prem.jpg",
                                                     :url                  "https://www.lotuseaters.com/premium-brokenomics-18-or-thomas-sowell-25-04-2023",
                                                     :excerpt              "Connor joins Dan to discuss one of the LotusEaters favourite Economic thinkers - the Legendary Thomas Sowell.",
                                                     :saved-audio-uuid     #uuid "7e4acdeb-623f-41fe-9587-93c8f01ec6b0"
                                                     :publish-date         #inst"2023-04-25T15:00:00.000-00:00"}
                                           #:episode{:title                "Big Ideas",
                                                     :thumbnail-origin-uri "https://images.ctfassets.net/khed0tvttjco/62r9kdzk9IbrqLRPBMcA0Y/5181641a530e5e1c1e7f30a93cc1250c/17.5_Big_-_Prem.jpg",
                                                     :url                  "https://www.lotuseaters.com/premium-brokenomics-17-or-five-big-innovation-platforms-18-04-23",
                                                     :excerpt              "The world is about to experience a combination of new technologies that could radically alter our economic trajectory and change the way we live and work forever - Dan looks at these technologies and what they might mean",
                                                     :saved-audio-uuid     #uuid "25de17df-86fa-4619-bf02-e545b383c911"
                                                     :publish-date         #inst"2023-04-18T15:00:00.000-00:00"}])))
       (response/content-type "application/rss+xml")))})



;;; SERVER COMPONENT ==============================

;; The indirection for `handler` and `init-key` enable tools.ns.refresh, without the need for suspend/resume. quite handy
(def handler
  (let [router (memoize router/router)]
    (fn [req]
      (try
        (or ((router routes) req)
          (response/not-found "Route not found"))
        (catch Exception e (log/error "Handler error" e))))))

(defmethod ig/init-key ::server
  [_ {:keys [jetty] :as options}]
  (let [options (assoc jetty :join? false)]
    (println "Starting server" options)
    (ring-jetty/run-jetty handler options)))

(defmethod ig/suspend-key! ::server
  [_ _])

(defmethod ig/resume-key ::server
  [_ _ _ _])

(defmethod ig/halt-key! ::server
  [_ server]
  (ring-jetty/stop-server server))
