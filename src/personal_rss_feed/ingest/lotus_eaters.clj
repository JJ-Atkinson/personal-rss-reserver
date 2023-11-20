(ns personal-rss-feed.ingest.lotus-eaters
  (:require
   [babashka.http-client :as bb.http]
   [clj-http.client :as http]
   [clojure.data.xml :as xml]
   [clojure.java.io]
   [integrant.core :as ig]
   [personal-rss-feed.ingest.lotus-eaters.download-file :as le.download-file]
   [personal-rss-feed.ingest.lotus-eaters.extract-audio :as le.extract-audio]
   [personal-rss-feed.ingest.lotus-eaters.fetch-metadata :as le.fetch-metadata]
   [personal-rss-feed.ingest.lotus-eaters.rss-feed-parser :as le.rss-feed-parser]
   [personal-rss-feed.ingest.lotus-eaters.shared :as le.shared]
   [remus]))

(defmethod ig/init-key ::lotus-eaters-ingest
  [_ {:keys [start-auto-poll?
             start-daily-feed-parse?
             apply-playwright-cli-fix?
             queue
             s3/s3
             ] :as options}]
  (when apply-playwright-cli-fix?
    ;; Required for nix.
    (System/setProperty "playwright.cli.dir" (System/getenv "PLAYWRIGHT_CLI_LOCATION")))

  (let [shared
        (-> options
          (assoc ::le.shared/queue queue
                 ::le.shared/s3 s3)
          (dissoc :queue)

          (le.extract-audio/init!)
          (le.download-file/init!)
          (le.fetch-metadata/init!)
          (cond->
            start-daily-feed-parse? (le.rss-feed-parser/init!)))]

    (reset! le.shared/!shared shared)
    shared)

  ;;(simple-queue/qcreate! queue {::queue/name :lotus-eaters/download-audio})
  ;;(simple-queue/qcreate! queue {::queue/name :lotus-eaters/download-video})
  ;;(simple-queue/qcreate! queue {::queue/name :lotus-eaters/transcribe-audio})
  ;;(simple-queue/qcreate! queue {::queue/name :lotus-eaters/upload-s3})

  )

(defmethod ig/suspend-key! ::lotus-eaters-ingest
  [_ _])

(defmethod ig/resume-key ::lotus-eaters-ingest
  [_ _ _ _])

(defmethod ig/halt-key! ::lotus-eaters-ingest
  [_ shared]
  (le.shared/halt! shared))
