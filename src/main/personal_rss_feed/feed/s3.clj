(ns personal-rss-feed.feed.s3
  (:require
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [babashka.http-client :as bb.http]
   [personal-rss-feed.ingest.shell-utils :refer [throwing-shell]]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as aws.creds]
   [integrant.core :as ig]
   [personal-rss-feed.ingest.file-utils :as file-utils]
   [personal-rss-feed.name-utils :as name-utils]
   [cognitect.aws.signers :as a.signers]
   [cognitect.aws.client.protocol :as client.protocol]
   [taoensso.timbre :as log])
  (:import (java.net URI URL)))

(defonce !s3 (atom {}))

(defn- to-uri
  "Properly encode a URL with spaces into a URI that http/get can handle."
  [s]
  (let [url (URL. s)]
    (.toString
     (URI. (.getProtocol url)
           (.getUserInfo url)
           (.getHost url)
           (.getPort url)
           (.getPath url)
           (.getQuery url)
           (.getRef url)))))

(defn content-length!
  [{:as s3 :keys [bucket-name client]} key]
  (:ContentLength (aws/invoke client
                              {:op      :HeadObject
                               :request {:Bucket bucket-name
                                         :Key    key}})))

(defn s3-name
  [s3 key]
  (str "s3://" (:bucket-name s3) "/" key))

(defn upload-file!
  [{:as s3 :keys [bucket-name client env-awscli]} key src-file {:as options :keys [content-type]}]
  (throwing-shell {:extra-env env-awscli}
                  "aws s3 cp"
                  "--content-type"
                  content-type
                  (.toString src-file)
                  (s3-name s3 key)))

(defn upload-uri!
  "Returns content-length. If the content length of the uploaded file does not match, an exception is thrown and the
   new s3 obj is deleted."
  [{:as s3 :keys [bucket-name client env-awscli]} uri key]
  (let [uri (to-uri uri)
        {:keys [body]
         {:strs [content-type content-length]} :headers}
        (http/get uri {:as :stream})
        content-length (Long/parseLong content-length)]
    (file-utils/with-tempfile [tmp-download (file-utils/create-temp-file key (name-utils/extension-of uri))]
                              (io/copy body tmp-download)
                              (upload-file! s3 key tmp-download {:content-type content-type}))

    (let [act-content-length (content-length! s3 key)]
      (when-not (= act-content-length content-length)
        (aws/invoke client
                    {:op      :DeleteObject
                     :request {:Bucket bucket-name
                               :Key    key}})
        (throw (ex-info "Download failed!"
                        {:uri                  uri
                         :dest-name            key
                         :expected-content-len content-length
                         :actual-content-len   act-content-length})))

      act-content-length)))

(defn download-object!
  "Download a file out of s3 to dest, which should be convertable to a file."
  [{:as s3 :keys [bucket-name client env-awscli]} key dest]
  (throwing-shell {:extra-env env-awscli}
                  "aws s3 cp"
                  (s3-name s3 key)
                  (.toString dest)))

(defn make-extra-env-awscli
  [options]
  {"AWS_ACCESS_KEY_ID"     (:access-key-id options)
   "AWS_SECRET_ACCESS_KEY" (:secret-access-key options)
   "AWS_DEFAULT_REGION"    (:region options)
   "AWS_ENDPOINT_URL"      (str "http://" (:hostname options) ":" (:port options))
  })

(defmethod ig/init-key ::s3
  [_ {:keys [hostname port region bucket-name access-key-id secret-access-key] :as options}]
  (let [s3
        {:client      (aws/client
                       {:api                  :s3
                        :endpoint-override    {:protocol :http
                                               :hostname hostname
                                               :port     port}
                        :region               region
                        :credentials-provider (aws.creds/basic-credentials-provider
                                               {:access-key-id     access-key-id
                                                :secret-access-key secret-access-key})})
         :bucket-name bucket-name
         :env-awscli  (make-extra-env-awscli options)}]
    (reset! !s3 s3)
    s3))

(defmethod ig/suspend-key! ::s3
  [_ _])

(defmethod ig/resume-key ::s3
  [_ _ _ _])

(defmethod ig/halt-key! ::s3
  [_ s3]
  (aws/stop (:client s3)))


;; #AWSS3Signatures https://github.com/cognitect-labs/aws-api/issues/263
(defmethod a.signers/sign-http-request "s3"
  [service endpoint credentials http-request]
  (a.signers/v4-sign-http-request service
                                  endpoint
                                  credentials
                                  (-> http-request
                                      (assoc-in [:headers "host"]
                                                (str (get-in http-request [:headers "host"]) ":3900")))
                                  :content-sha256-header?
                                  true))




(comment
  (keys (aws/ops (:client @!s3)))
  (aws/doc (:client @!s3) :PutObject)
  (aws/doc (:client @!s3) :ListObjects)

  (aws/invoke (:client @!s3)
              {:op      :HeadObject
               :request {:Bucket "lotus-eaters"
                         :Key    "example"}})

  (download-object! @!s3 "video-a466e883-b8c1-422f-9a01-f2a566738dfd.mp4" "/tmp/file222222.mp4")
  (download-object! @!s3 "audio-697d57bb-3f73-4a00-9f61-49f7c475d37c.mp3" "/tmp/filewhisper.mp3")
  (upload-file!
   @!s3
   "example"
   "/home/jarrett/Downloads/example.html"
   {:content-type "text/html"})

  (aws/invoke (:client @!s3)
              {:op      :ListObjects
               :request {:Bucket "lotus-eaters"}})

  ;; Clear the CI bucket
  (let [objects (aws/invoke (:client @!s3)
                            {:op      :ListObjects
                             :request {:Bucket "lotus-eaters"}})]
    (:Contents objects)
    objects
    #_(doseq [obj (:Contents objects)]
        (println "Deleting" (:Key obj))
        (aws/invoke (:client @!s3)
                    {:op      :DeleteObject
                     :request {:Bucket "lotus-eaters-ci"
                               :Key    (:Key obj)}}))))
