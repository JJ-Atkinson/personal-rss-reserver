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
   [personal-rss-feed.time-utils :as time-utils]
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
          s3-video-key (name-utils/format-video ep-uuid original-uri)
          s3-audio-key (name-utils/format-audio ep-uuid original-uri)]

      (if (:episode/audio-content-length episode)
        (do
          (log/info "No need to extract audio for existing audio file" url)
          (simple-queue/qcomplete! queue id {:audio-file-already-existed? true}))
        (do
          (log/info "Extracting audio from the video for the episode" url)
          (tempfile/with-tempfile [video-temp (Files/createTempFile "video-dest" (name-utils/extension-of original-uri)
                                                (make-array FileAttribute 0))
                                   audio-temp (Files/createTempFile "audio-dest" ".mp3"
                                                (make-array FileAttribute 0))]
            (s3/download-object! s3 s3-video-key video-temp)
            (shell "ffmpeg" "-i" (.toString video-temp)
              "-q:a 0"                                      ;; variable bitrate
              "-map a"                                      ;; Extract audio only, no subtitles
              "-nostdin"                                    ;; Force ffmpeg into non interactive mode
              "-y"                                          ;; Automatically allow file overwrite (shouldn't happen)
              (.toString audio-temp))
            (s3/upload-file! s3 s3-audio-key (.toString audio-temp) {:content-type "audio/mpeg"}))
          (simple-queue/qcomplete! queue id))))
    (catch Exception e
      (simple-queue/qerror! queue id {:exception                e
                                      ::simple-queue/retryable? true}))))

(defn init!
  [shared]
  (le.shared/start-queue! shared
    {:queue-conf {::queue/name                ::extract-audio-queue
                  ::queue/default-retry-limit 4
                  ::queue/lockout?-fn         (simple-queue/comp-lockout_rate-limit
                                                (time-utils/queue-lockout-backoff-retry
                                                  {:base-s-backoff 30})
                                                (simple-queue/lockout?-intensive-cpu-tasks))}
     :poll-ms    1000
     :poll-f     #'extract-audio}))

(comment
  (def dest (Files/createTempFile "audio-file" ".mp3" (make-array FileAttribute 0)))
  (s3/download-object! (::le.shared/s3 @le.shared/!shared) "audio-a466e883-b8c1-422f-9a01-f2a566738dfd.mp3" (.toString dest))

  (def out (Files/createTempFile "audio-file-out" ".mp3" (make-array FileAttribute 0)))
  (shell "ffmpeg" "-i" (.toString dest) "-q:a" "0" "-map" "a" (.toString out))

  (s3/upload-file! (::le.shared/s3 @le.shared/!shared) "audio-1.mp3" (.toString out) {:content-type "audio/mpeg"})
  )