(ns personal-rss-feed.feed.s3
  (:require
   [clojure.java.io :as io]
   [babashka.http-client :as bb.http]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as aws.creds]
   [com.grzm.awyeah.http-client :as awyeah.http]
   [integrant.core :as ig])
  (:import (java.net URI URL)))

(defonce !s3 (atom {}))

(defn- to-uri 
  [s]
  (let [url (URL. s)]
    (.toString
      (URI. (.getProtocol url) (.getUserInfo url) (.getHost url) (.getPort url) (.getPath url) (.getQuery url) (.getRef url)))))

(defn content-length 
  [{:as s3 :keys [bucket-name client]} key]
  (:ContentLength (aws/invoke client {:op      :HeadObject
                                      :request {:Bucket bucket-name
                                                :Key    key}})))

(defn upload-uri!
  "Returns content-length. If the content length of the uploaded file does not match, an exception is thrown and the
   new s3 obj is deleted."
  [{:as s3 :keys [bucket-name client]} uri key]
  (let [uri (to-uri uri)
        {:strs [content-type content-length]} (:headers (bb.http/head uri))
        content-length (Long/parseLong content-length)
        {:keys [body]} (bb.http/get uri {:as :stream})]
    (with-open [body body]
      (aws/invoke client {:op      :PutObject
                          :request {:Bucket      bucket-name
                                    :Key         key
                                    :ContentType content-type
                                    :Body        body}}))

    (let [act-content-length (content-length s3 key)]
      (when-not (= act-content-length content-length)
        (aws/invoke client {:op      :DeleteObject
                            :request {:Bucket bucket-name
                                      :Key    key}})
        (throw (ex-info "Download failed!" {:uri                  uri
                                            :dest-name            key
                                            :expected-content-len content-length
                                            :actual-content-len   act-content-length})))

      act-content-length)))

(defn download-object!
  "Download a file out of s3 to dest, which should be convertable to a file."
  [{:as s3 :keys [bucket-name client]} key dest]
  (with-open [body (:Body (aws/invoke client {:op      :GetObject
                                              :request {:Bucket bucket-name
                                                        :Key    key}}))]
    (io/copy body (io/file dest))))

(defn upload-file!
  [{:as s3 :keys [bucket-name client]} key src-file {:as options :keys [content-type]}]
  (with-open [body (io/input-stream src-file)]
    (aws/invoke client {:op      :PutObject
                        :request {:Bucket bucket-name
                                  :Key    key
                                  :ContentType content-type
                                  :Body   body}})))

(defmethod ig/init-key ::s3
  [_ {:keys [hostname port region bucket-name access-key-id secret-access-key] :as options}]
  (let [s3
        {:client      (aws/client
                        {:api                  :s3
                         :endpoint-override    {:protocol :http
                                                :hostname hostname
                                                :port     port}
                         :http-client          (awyeah.http/create {})
                         :region               region
                         :credentials-provider (aws.creds/basic-credentials-provider
                                                 {:access-key-id     access-key-id
                                                  :secret-access-key secret-access-key})})
         :bucket-name bucket-name}]
    (reset! !s3 s3)
    s3))

(defmethod ig/suspend-key! ::s3
  [_ _])

(defmethod ig/resume-key ::s3
  [_ _ _ _])

(defmethod ig/halt-key! ::s3
  [_ s3]
  (aws/stop (:client s3)))

(comment
  (aws/doc (:client @!s3) :PutObject)
  (aws/doc (:client @!s3) :ListObjects)
  (upload-uri! (:client @!s3) "..." "test.mp3")

  (aws/invoke (:client @!s3) {:op      :HeadObject
                              :request {:Bucket "lotus-eaters"
                                        :Key "audio-f4861adb-faa1-4e8e-ae6d-462c5942c80d.mp3"}})

  ;; Clear the CI bucket
  (let [objects (aws/invoke (:client @!s3) {:op      :ListObjects
                                            :request {:Bucket "lotus-eaters-ci"}})]
    (:Contents objects)
    #_(doseq [obj (:Contents objects)]
        (println "Deleting" (:Key obj))
        (aws/invoke @!s3 {:op      :DeleteObject
                          :request {:Bucket "lotus-eaters-ci"
                                    :Key    (:Key obj)}}))))