(ns personal-rss-feed.ingest.lotus-eaters.download-file
  (:require [datalevin.core :as d]
            [dev.freeformsoftware.simple-queue.core :as simple-queue]
            [dev.freeformsoftware.simple-queue.queue :as queue]
            [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
            [personal-rss-feed.feed.s3 :as s3]
            [personal-rss-feed.feed.db :as db]
            [personal-rss-feed.name-utils :as name-utils]
            [personal-rss-feed.ingest.lotus-eaters.shared :as le.shared]
            [personal-rss-feed.time-utils :as time-utils]
            [taoensso.timbre :as log]))


(defn download-episode
  [!shared {::queue-item/keys                      [id]
            {:keys [:episode/url ::download-type]} ::queue-item/data
            :as                                    queue-item}]
  (log/info "Downloading file" url download-type)
  (try
    (let [episode        (db/episode-by-url (d/db (:db/conn @!shared)) url)
          ep-id          (:episode/id episode)
          download-url   (case download-type
                           ::audio (:episode/audio-origin-uri episode)
                           ::video (:episode/video-original-uri episode))
          dest-key       (case download-type
                           ::audio (name-utils/format-audio ep-id)
                           ::video (name-utils/format-video ep-id))
          content-length (s3/upload-uri! (::le.shared/s3 @!shared) download-url dest-key)]
      (when (= ::audio download-type)
        (db/save-episode! (:db/conn @!shared) {:episode/url                  url
                                               :episode/audio-content-length content-length})))
    (catch Exception e
      (simple-queue/qerror! (::le.shared/queue @!shared) id {:exception                (pr-str e)
                                                             ::simple-queue/retryable? true}))))

(defn init!
  [!shared]
  (le.shared/start-queue! !shared
    {:queue-conf {::queue/name                ::download-queue
                  ::queue/default-retry-limit 3
                  ::queue/rate-limit-fn       (time-utils/queue-rate-limit-x-per-period
                                                {:period-s    (* 60 60 24)
                                                 :limit-count (:downloads-per-day @!shared)})
                  ::queue/timeout?-fn         (simple-queue/default-timeout?-fn (* 1000 200))
                  ::queue/lockout?-fn         (time-utils/queue-lockout-backoff-retry
                                                {:base-s-backoff (* 60 60 3)})}
     :poll-ms    1000
     :poll-f     #'download-episode}))

(comment
  (dev.freeformsoftware.simple-queue.core/qpeek!
    (::le.shared/queue @@le.shared/!!shared)
    ::download-queue)

  (download-episode @le.shared/!!shared
    (dev.freeformsoftware.simple-queue.core/qpop!
      (::le.shared/queue @@le.shared/!!shared)
      ::download-queue)))
