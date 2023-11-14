(ns personal-rss-feed.ingest.lotus-eaters
  (:require
   [babashka.http-client :as bb.http]
   [clj-http.client :as http]
   [clojure.data.xml :as xml]
   [clojure.java.io]
   [integrant.core :as ig]
   [personal-rss-feed.ingest.lotus-eaters.fetch-metadata :as le.fetch-metadata]
   [personal-rss-feed.ingest.lotus-eaters.rss-feed-parser :as le.rss-feed-parser]
   [personal-rss-feed.ingest.lotus-eaters.shared :as le.shared]
   [remus]))

(comment
  (def resp
    (http/get
      "https://lotuseaters.com/feed/category/brokenomics"))
  (def resp2 (remus/parse-url "https://lotuseaters.com/feed/category/brokenomics"))
  (xml/parse-str (:body resp))

  (rss-str->episodes (:body resp))

  (http/head "https://cdn.lotuseaters.com/23.03.28-Brokenomics17-5_Big_Innovation_Platforms(P).mp3")
  )




(comment
  (def s3 (aws/client {:api               :s3
                       :endpoint-override {:protocol :http
                                           :hostname "garage-ct.lan"
                                           :port     3900}
                       :region            "us-west-1"
                       }))
  (aws/doc s3 :PutObject)
  (aws/invoke s3 {:op :ListBuckets})


  (let [request (bb.http/get "https://rss-feeds.us-southeast-1.linodeobjects.com/dads.mp3" {:as :stream})]
    (with-open [body (:body request)]
      (println (type body))
      (aws/invoke s3 {:op      :PutObject
                      :request {:Bucket        "lotus-eaters"
                                :Key           "dads.mp3"
                                :ContentType   (get-in request [:headers "content-type"])
                                :ContentLength (Integer. (get-in request [:headers "content-length"] 0))
                                :Body          body}})))

  (with-open [body (clojure.java.io/input-stream "dads-linode.mp3")]
    (println (type body))
    (aws/invoke s3 {:op      :PutObject
                    :request {:Bucket "lotus-eaters"
                              :Key    "dads.mp3"
                              :Body   body}}))

  (java.util.Date. "2023-10-17T15:00:00+00:00"))


(defmethod ig/init-key ::lotus-eaters-ingest
  [_ {:keys [start-feed-refresh?
             apply-playwright-cli-fix?
             queue
             ] :as options}]
  (when apply-playwright-cli-fix?
    ;; Required for nix.
    (System/setProperty "playwright.cli.dir" (System/getenv "PLAYWRIGHT_CLI_LOCATION")))

  (let [!shared (atom options)]
    (reset! le.shared/!!shared !shared)
    (le.fetch-metadata/init! !shared)
    
    (when start-feed-refresh?
      (le.rss-feed-parser/init! !shared))

    !shared)

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
  [_ !shared]
  (le.shared/halt! !shared))
