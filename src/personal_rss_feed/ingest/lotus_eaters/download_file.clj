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
  [{:db/keys         [conn]
    ::le.shared/keys [queue s3]}
   {::queue-item/keys                      [id]
    {:keys [:episode/url ::download-type]} ::queue-item/data
    :as                                    queue-item}]
  (log/info "Downloading file" url download-type)
  (try
    (let [episode        (db/episode-by-url (d/db conn) url)
          ep-id          (:episode/uuid episode)
          download-uri   (case download-type
                           ::audio (:episode/audio-original-uri episode)
                           ::video (:episode/video-original-uri episode))
          dest-key       (case download-type
                           ::audio (name-utils/format-audio ep-id)
                           ::video (name-utils/format-video ep-id))
          content-length (s3/upload-uri! s3 download-uri dest-key)]
      (cond (= ::audio download-type)
            (db/save-episode! conn {:episode/url                  url
                                    :episode/audio-content-length content-length})
            :else nil)
      (log/info "Successfully downloaded the file!" url dest-key)
      (simple-queue/qcomplete! queue id {:destination    dest-key
                                         :content-length content-length
                                         :download-url   download-uri}))
    (catch Exception e
      (simple-queue/qerror! queue id {:exception                (pr-str e)
                                      ::simple-queue/retryable? true}))))

(defn init!
  [shared]
  (le.shared/start-queue! shared
    {:queue-conf {::queue/name                ::download-queue
                  ::queue/default-retry-limit 3
                  ::queue/rate-limit-fn       (time-utils/queue-rate-limit-x-per-period
                                                {:period-s    (* 60 60 24)
                                                 :limit-count (:downloads-per-day shared)})
                  ::queue/timeout?-fn         (simple-queue/default-timeout?-fn (* 1000 200))
                  ::queue/lockout?-fn         (time-utils/queue-lockout-backoff-retry
                                                {:base-s-backoff (* 60 60 3)})}
     :poll-ms    1000
     :poll-f     #'download-episode}))

(comment
  (simple-queue/qpeek!
    (::le.shared/queue @le.shared/!shared)
    ::download-queue)

  (simple-queue/qview
    (::le.shared/queue @le.shared/!shared)
    ::download-queue)
  
  (simple-queue/qsubmit! (::le.shared/queue @le.shared/!shared)
    (assoc (#'simple-queue/resolve!i (::le.shared/queue @le.shared/!shared) #uuid "50b3a4d9-176e-44bc-9d19-f34798d4dbc2")
      ::queue-item/priority Long/MAX_VALUE
      ::queue-item/retry-count 0))

  (swap! simple-queue/*manual-unlock-1*
    conj ::download-queue)

  (do
    (download-episode
      @le.shared/!shared
      (#'simple-queue/resolve!i (::le.shared/queue @le.shared/!shared) #uuid "50b3a4d9-176e-44bc-9d19-f34798d4dbc2"))
    nil)

  (download-episode @le.shared/!shared
    (simple-queue/qpop!
      (::le.shared/queue @le.shared/!shared)
      ::download-queue)))
