(ns personal-rss-feed.feed.routes
  (:require
   [personal-rss-feed.feed.db :as db]
   [personal-rss-feed.ingest.lotus-eaters.download-file :as le.download-file]
   [clojure.data.xml :as xml]
   [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
   [ring.util.response :as response]
   [personal-rss-feed.name-utils :as name-utils]
   [hiccup.core :as hiccup])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Date Locale)))


(defn format-time
  [^Date t]
  (when t
    (.format (DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss ZZ" Locale/ENGLISH)
             (.atOffset (.toInstant t) ZoneOffset/UTC))))

(defn generate-description
  [{:as        config
    :s3/keys   [public-s3-prefix]
    :feed/keys [public-feed-address secret-path-segment]}
   {:episode/keys [excerpt video-content-length video-original-uri id uuid url]}]
  (hiccup/html
   [:div
    [:div excerpt] [:br]
    [:span "ID:" id] [:br]
    [:a {:href url} "Lotus eaters website link"] [:br]
    (cond
      video-content-length
      (list [:a {:href (name-utils/format-video public-s3-prefix uuid video-original-uri)} "Watch Now"] [:br])

      video-original-uri
      (list [:a {:href video-original-uri} "Watch Now on LE CDN"]
            [:br]
            [:a {:href (str public-feed-address secret-path-segment "/content/video/" id)} "Start video download now."])

      :else
      [:span "No video for this episode."])]))

(defn write-podcast-feed
  [config
   podcast
   episodes]
  (xml/element
   :rss
   {:version        "2.0"
    "xmlns:atom"    "http://www.w3.org/2005/Atom/"
    "xmlns:rdf"     "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    "xmlns:itunes"  "http://www.itunes.com/dtds/podcast-1.0.dtd"
    "xmlns:content" "http://purl.org/rss/1.0/modules/content/"}
   (apply
    xml/element
    :channel
    {}
    (xml/element :title {} (:podcast/title podcast))
    (xml/element :pubDate
                 {}
                 (-> episodes
                     last
                     :episode/publish-date
                     format-time))
    (xml/element :description {} (:podcast/description podcast))
    (xml/element :language {} "en-US")
    (xml/element "itunes:author" {} "Lotus Eaters")
    (xml/element "itunes:image"
                 {:href (or
                         (some->> podcast
                                  :podcast/generated-icon-relative-uri
                                  (str (:s3/public-s3-prefix config)))
                         (:podcast/icon-uri podcast))})

    (->> (or (seq episodes)
             [{:episode/url                  "dne"
               :episode/audio-content-length 1
               :episode/title                "Does not exist"
               :episode/id                   (str "fake" (:podcast/id podcast))
               ::override-url                "blank.mp3" ;; This was manually added to the s3 host
               :episode/publish-date         (Date.)}])
         (map
          (fn [episode]
            (xml/element
             :item
             {}
             (xml/element :title {} (:episode/title episode))
             (xml/element :description {} (:episode/excerpt episode))
             (xml/element :enclosure
                          {:url    (or
                                    (some->> episode
                                             ::override-url
                                             (str (:s3/public-s3-prefix config)))
                                    (name-utils/format-audio (:s3/public-s3-prefix config) (:episode/uuid episode)))
                           :length (:episode/audio-content-length episode)
                           :type   "audio/mp3"})
             (xml/element :pubDate
                          {}
                          (-> episode
                              :episode/publish-date
                              format-time))
             (xml/element :guid
                          {}
                          (-> episode
                              :episode/id
                              str))
             (xml/element "content:encoded"
                          {}
                          (xml/cdata (generate-description config episode)))
             (xml/element "itunes:image"
                          {:href
                           "http://relayfm.s3.amazonaws.com/uploads/broadcast/image/17/cortex_artwork.png"}))))))))

(defn download-status-page
  [config ep-url type]
  (let [queue-item (le.download-file/download-now!
                    (:lotus-eaters-ingest config)
                    {:episode/url ep-url
                     ::le.download-file/download-type type})]
    (response/response
     (hiccup/html
      [:body
       [:h1 "Download initiated"]
       [:span "Status:" (name (::queue-item/status queue-item))]]))))

(defn routes
  "Routes wrapped with individual middleware"
  [{:keys [db/conn feed/secret-path-segment feed/public-feed-address] :as config}]
  {(str "GET /" secret-path-segment "/feeds")
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
                   (let [link (str public-feed-address secret-path-segment "/feed/" id)]
                     [:a {:href link} link])])
                podcasts)]]))))

   (str "GET /" secret-path-segment "/feed/*")
   (fn [{[id] :path-params}]
     (when-let [podcast (db/podcast-by-id conn id)]
       (let [episodes (filter :episode/audio-content-length
                              (db/podcast-feed conn (:podcast/feed-uri podcast)))]
         (-> (response/response
              (xml/emit-str (write-podcast-feed config podcast episodes)))
             (response/content-type "application/rss+xml")))))

   (str "GET /" secret-path-segment "/content/video/*")
   (fn [{[id] :path-params}]
     (when-let [episode (db/episode-by-id conn id)]
       (if (:episode/video-content-length episode)
         (response/redirect
          (name-utils/format-video (:s3/public-s3-prefix config)
                                   (:episode/uuid episode)
                                   (:episode/video-original-uri episode))
          :moved-permanently)
         (download-status-page config (:episode/url episode) ::le.download-file/video))))})

(defn safe-prefixes 
  [{:keys [feed/secret-path-segment] :as config}]
  ["/" secret-path-segment])
