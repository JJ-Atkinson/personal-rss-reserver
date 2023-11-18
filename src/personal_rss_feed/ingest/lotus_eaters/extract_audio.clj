(ns personal-rss-feed.ingest.lotus-eaters.extract-audio
  (:require
   [datalevin.core :as d]
   [dev.freeformsoftware.simple-queue.core :as simple-queue]
   [dev.freeformsoftware.simple-queue.queue :as queue]
   [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
   [personal-rss-feed.feed.s3 :as s3]
   [personal-rss-feed.feed.db :as db]
   [personal-rss-feed.name-utils :as name-utils]
   [personal-rss-feed.ingest.lotus-eaters.shared :as le.shared]
   [tempfile.core :as tempfile]
   [babashka.process :refer [shell process exec]]
   [taoensso.timbre :as log])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn extract-audio
  [{:db/keys         [conn]
    ::le.shared/keys [queue s3]}
   {::queue-item/keys      [id]
    {:keys [:episode/url]} ::queue-item/data
    :as                    queue-item}]
  (try
    (let [episode      (db/episode-by-url (d/db conn) url)
          ep-uuid      (:episode/uuid episode)
          original-uri (:episode/video-original-uri episode)
          s3-key       (name-utils/format-video ep-uuid original-uri)]

      (tempfile/with-tempfile [video-temp (Files/createTempFile "video-dest" (name-utils/extension-of original-uri)
                                            (make-array FileAttribute 0))
                               audio-temp (Files/createTempFile "audio-dest" ".mp3"
                                            (make-array FileAttribute 0))]
        (s3/download-object! s3 s3-key video-temp)
        (shell "ffmpeg" "-i" (.toString video-temp))
        )
      (log/info "Extracting audio from the video for the episode" url))
    (catch Exception e
      (simple-queue/qerror! queue id {:exception                e
                                      ::simple-queue/retryable? true}))))

(Files/createTempFile "video-dest" (name-utils/extension-of original-uri)
  (make-array FileAttribute 0))


(defn init!
  [shared]
  (le.shared/start-queue! shared
    {:queue-conf {::queue/name                ::extract-audio-queue
                  ::queue/default-retry-limit 3}
     :poll-ms    1000
     :poll-f     #'download-episode}))