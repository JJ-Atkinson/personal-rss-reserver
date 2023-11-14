(ns personal-rss-feed.feed.s3
  (:require
   [clojure.java.io :as io]
   [babashka.http-client :as bb.http]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as aws.creds]
   [com.grzm.awyeah.http-client :as awyeah.http]
   [integrant.core :as ig]))

(defonce !s3 (atom {}))

(defn upload-uri!
  "Returns content-length. If the content length of the uploaded file does not match, an exception is thrown and the
   new s3 obj is deleted."
  [s3 uri key]
  (let [{:strs [content-type content-length]} (:headers (bb.http/head uri))
        content-length (Long/parseLong content-length)
        {:keys [body]} (bb.http/get uri {:as :stream})]
    (with-open [body body]
      (aws/invoke s3 {:op      :PutObject
                      :request {:Bucket      "lotus-eaters"
                                :Key         key
                                :ContentType content-type
                                :Body        body}}))

    (let [act-content-length (:ContentLength (aws/invoke s3 {:op      :HeadObject
                                                             :request {:Bucket "lotus-eaters"
                                                                       :Key    key}}))]
      (when-not (= act-content-length content-length)
        (aws/invoke s3 {:op      :DeleteObject
                        :request {:Bucket "lotus-eaters"
                                  :Key    key}})
        (throw (ex-info "Download failed!" {:uri                  uri
                                            :dest-name            key
                                            :expected-content-len content-length
                                            :actual-content-len   act-content-length})))

      act-content-length)))

(defn download-object!
  "Download a file out of s3 to dest, which should be convertable to a file."
  [s3 key dest]
  (with-open [body (:Body (aws/invoke s3 {:op      :GetObject
                                          :request {:Bucket "lotus-eaters"
                                                    :Key    key}}))]
    (io/copy body (io/file dest))))


(defmethod ig/init-key ::s3
  [_ {:keys [hostname port region access-key-id secret-access-key] :as options}]
  (let [client
        (aws/client
          {:api                  :s3
           :endpoint-override    {:protocol :http
                                  :hostname hostname
                                  :port     port}
           :http-client          (awyeah.http/create {})
           :region               region
           :credentials-provider (aws.creds/basic-credentials-provider
                                   {:access-key-id     access-key-id
                                    :secret-access-key secret-access-key})})]
    (reset! !s3 client)
    client))

(defmethod ig/suspend-key! ::s3
  [_ _])

(defmethod ig/resume-key ::s3
  [_ _ _ _])

(defmethod ig/halt-key! ::s3
  [_ s3]
  (aws/stop s3))

(comment
  (aws/doc @!s3 :GetObject)
  (upload-uri! @!s3 "https://cdn.lotuseaters.com/23.11.02%20-%20Symposium44%20Premium.mp3" "test.mp3"))