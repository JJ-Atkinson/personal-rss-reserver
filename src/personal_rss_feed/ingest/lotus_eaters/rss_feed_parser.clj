(ns personal-rss-feed.ingest.lotus-eaters.rss-feed-parser
  (:require [babashka.http-client :as bb.http]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [datalevin.core :as d]
            [chime.core :as chime]
            [dev.freeformsoftware.simple-queue.core :as simple-queue]
            [personal-rss-feed.ingest.lotus-eaters.shared :as le.shared]
            [personal-rss-feed.feed.db :as db]
            [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
            [personal-rss-feed.ingest.lotus-eaters.fetch-metadata :as le.fetch-metadata]
            [taoensso.timbre :as log])
  (:import (java.time Duration Instant LocalDateTime Period ZoneId LocalTime ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Date)))

(defn parse-description
  [desc-str]
  (drop 1
    (re-matches #".*\<a href=\"(.*)\" ?>.*\<img src=\"(.*)\" ?\/>.*\<br\>(.*).*<br>"
      (-> desc-str
        (str/trim)
        (str/replace "\t" "")
        (str/replace "\n" "")))))

(defn content
  [xml-tag]
  (first (:content xml-tag)))

(defn attribute
  ([key as value-extraction]
   (fn [xml--attrs-list]
     (as-> (filter #(= key (:tag %)) xml--attrs-list) $
       (first $)
       (value-extraction $)
       {as $})))
  ([key value-extraction]
   (attribute key key value-extraction)))

(defn navigate
  [path extractor]
  (fn [xml]
    (extractor (path xml))))

(defn map-accumulator
  [extractors]
  (fn [xml]
    (into {}
      (map (fn [ex] (ex xml)))
      extractors)))

(defn attributes
  ([key as value-extraction]
   (fn [xml--attrs-list]
     (as-> (filter #(= key (:tag %)) xml--attrs-list) $
       (map value-extraction $)
       {as $})))
  ([key value-extraction]
   (attributes key key value-extraction)))

(defn parse-date
  [s]
  (when-let [ldt (some-> s (LocalDateTime/parse DateTimeFormatter/ISO_OFFSET_DATE_TIME))]
    (Date/from (.toInstant (.atZone ldt (ZoneId/of "UTC"))))))

(def rss-str->episodes
  (comp
    (fn [intermediate]
      {:episodes (->> (:items intermediate)
                   (map (partial merge (select-keys intermediate [:podcast/id]))))
       :podcast  (dissoc intermediate :items)})
    (map-accumulator
      [(attribute :title :podcast/title content)
       (attribute :icon :podcast/icon-uri content)
       (attribute :updated :podcast/updated-at (comp parse-date content))
       (attribute :description :podcast/description content)
       (attributes :item :items
         (navigate
           :content
           (map-accumulator
             [(attribute :title :episode/title #(str/trim (second (str/split (content %) #"\|"))))
              (attribute :description :episode/thumbnail-origin-uri #(nth (parse-description (content %)) 1))
              (attribute :description :episode/url #(nth (parse-description (content %)) 0))
              (attribute :description :episode/excerpt #(nth (parse-description (content %)) 2))
              (attribute :pubDate :episode/publish-date (comp parse-date content))])))])
    #(-> (xml/parse-str %) :content first :content)))

(defn parse-new-feed-items
  [conn queue feed-uri]
  (log/info "Parsing feed" feed-uri)
  (try
    (let [body (:body (bb.http/get feed-uri))
          {:keys [episodes podcast]} (rss-str->episodes body)
          db   (d/db conn)]
      (log/info "Found episodes" episodes)
      (db/save-podcast! conn podcast)
      (doseq [ep (remove #(db/known-episode? db (:episode/url %)) episodes)]
        (db/save-episode! conn (assoc ep
                                 :episode/podcast feed-uri))
        (simple-queue/qsubmit! queue #::queue-item{:queue    ::le.fetch-metadata/fetch-metadata-queue
                                                   :id       (random-uuid)
                                                   :data     ep
                                                   :priority (or (some-> ep :episode/publish-date (.getTime)) ;; Priority newest->oldest
                                                               (.getTime (Date.))) 
                                                   })))
    (catch Exception e
      (log/error "Failed to parse new feed items!" e))))

(defn parse-all-feeds
  [conn queue]
  (log/info "Parsing rss feeds for new episodes")
  (doseq [{feed-uri :podcast/feed-uri} (db/known-podcasts conn)]
    (parse-new-feed-items conn queue feed-uri)
    (Thread/sleep 1234)                                     ;; Don't swamp the server.
    ))

(defn make-timeline
  "Now, and 3, 4, and 5:30pm GMT"
  []
  (let [daily-at (fn [h-gmt m] (chime/periodic-seq (-> (LocalTime/of h-gmt m)
                                                     (.adjustInto (ZonedDateTime/now (ZoneId/of "GMT")))
                                                     (.toInstant))
                                 (Period/ofDays 1)))]
    (cons (.plusSeconds (Instant/now) 3)
      (chime/without-past-times
        (interleave
          (daily-at 15 0)
          (daily-at 16 0)
          (daily-at 17 30))))))

(comment (take 10 (make-timeline)))

(defn init!
  [shared]
  (log/info "Starting rss feed parser task to run at" (take 5 (make-timeline)) "...")
  (-> shared
    (update ::le.shared/close-on-halt conj
      (chime/chime-at
        (make-timeline)
        (fn [t] (parse-all-feeds (:db/conn shared) (::le.shared/queue shared)))))))

(comment
  (def resp
    (bb.http/get
      "https://lotuseaters.com/feed/category/brokenomics"))
  (def resp2 (remus/parse-url "https://lotuseaters.com/feed/category/brokenomics"))
  (xml/parse-str (:body resp))

  (rss-str->episodes (:body resp))

  (http/head "https://cdn.lotuseaters.com/23.03.28-Brokenomics17-5_Big_Innovation_Platforms(P).mp3")
  )