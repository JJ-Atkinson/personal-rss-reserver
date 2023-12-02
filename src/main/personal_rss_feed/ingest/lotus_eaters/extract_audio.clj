(ns personal-rss-feed.ingest.lotus-eaters.extract-audio
  (:require
   [datalevin.core :as d]
   [dev.freeformsoftware.simple-queue.core :as simple-queue]
   [dev.freeformsoftware.simple-queue.queue :as queue]
   [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
   [personal-rss-feed.ingest.file-utils :as file-utils]
   [personal-rss-feed.feed.s3 :as s3]
   [personal-rss-feed.feed.db :as db]
   [personal-rss-feed.name-utils :as name-utils]
   [personal-rss-feed.ingest.lotus-eaters.shared :as le.shared]
   [personal-rss-feed.time-utils :as time-utils]
   [personal-rss-feed.ingest.shell-utils :refer [throwing-shell]]
   [taoensso.timbre :as log])
  )

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
          s3-audio-key (name-utils/format-audio ep-uuid)]

      (if (:episode/audio-content-length episode)
        (do
          (log/info "No need to extract audio for existing audio file" url ", saved at " s3-audio-key)
          (simple-queue/qcomplete! queue id {:audio-file-already-existed? true}))
        (do
          (log/info "Extracting audio from the video for the episode" url)
          (file-utils/with-tempfile
            [video-temp (file-utils/create-temp-file "video-dest" (name-utils/extension-of original-uri))
             audio-temp (file-utils/create-temp-file "audio-dest" ".mp3")]
            (s3/download-object! s3 s3-video-key video-temp)
            (throwing-shell "ffmpeg" "-i" (.toString video-temp)
              "-q:a" "0"                                      ;; variable bitrate
              "-map" "a"                                      ;; Extract audio only, no subtitles
              "-nostdin"                                    ;; Force ffmpeg into non interactive mode
              "-y"                                          ;; Automatically allow file overwrite (shouldn't happen)
              (.toString audio-temp))
            (s3/upload-file! s3 s3-audio-key (.toString audio-temp) {:content-type "audio/mpeg"})
            (db/save-episode! conn (assoc episode
                                     :episode/audio-content-length (s3/content-length! s3 s3-audio-key))))
          (simple-queue/qcomplete! queue id)
          nil)))
    (catch Exception e
      (simple-queue/qerror! queue id {:exception                (pr-str e)
                                      ::simple-queue/retryable? true})
      nil)))

(defn init!
  [shared]
  (le.shared/start-queue! shared
    {:queue-conf {::queue/name                ::extract-audio-queue
                  ::queue/default-retry-limit 4
                  ::queue/lockout?-fn         (simple-queue/comp-or_lockout_rate-limit
                                                (time-utils/queue-lockout-backoff-retry
                                                  {:base-s-backoff 30})
                                                (simple-queue/lockout?-intensive-cpu-tasks))
                  ::queue/timeout?-fn (simple-queue/default-timeout?-fn (* 1000 60 60 2))}
     :poll-ms    1000
     :poll-f     #'extract-audio}))

(comment
  (def dest (Files/createTempFile "audio-file" ".mp3" (make-array FileAttribute 0)))
  (s3/download-object! (::le.shared/s3 @le.shared/!shared) "audio-a466e883-b8c1-422f-9a01-f2a566738dfd.mp3" (.toString dest))

  (def out (Files/createTempFile "audio-file-out" ".mp3" (make-array FileAttribute 0)))
  (throwing-shell "ffmpeg" "-i" (.toString dest) "-q:a" "0" "-map" "a" (.toString out))

  (s3/upload-file! (::le.shared/s3 @le.shared/!shared) "audio-1.mp3" (.toString out) {:content-type "audio/mpeg"})
  
  (simple-queue/all-un-resolved-errors
    (::le.shared/queue @le.shared/!shared)
    ::extract-audio-queue)

  (simple-queue/qresubmit-item!
    (::le.shared/queue @le.shared/!shared)
    (::queue-item/id (first
                       (simple-queue/all-un-resolved-errors
                         (::le.shared/queue @le.shared/!shared)
                         ::extract-audio-queue))))
  
  (extract-audio @le.shared/!shared
    {::queue-item/data {:episode/url "https://www.lotuseaters.com/premium-live-lads-hour-10-or-birthday-special-09-11-2023"}})

  (simple-queue/qpeek!
    (::le.shared/queue @le.shared/!shared)
    ::extract-audio-queue)


  (#'simple-queue/update!q
   (::le.shared/queue @le.shared/!shared)
   ::extract-audio-queue
   #(assoc % ::queue/lockout?-fn (simple-queue/comp-or_lockout_rate-limit
                                   (time-utils/queue-lockout-backoff-retry
                                     {:base-s-backoff 30})
                                   (simple-queue/lockout?-intensive-cpu-tasks))))

  (simple-queue/resolve!i
    (::le.shared/queue @le.shared/!shared)
    #uuid"721009f2-3b55-440b-9238-41b50bb51b04")
  )