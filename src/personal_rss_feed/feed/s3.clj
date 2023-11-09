(ns personal-rss-feed.feed.s3
  (:require
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as aws.creds]
   [com.grzm.awyeah.http-client :as awyeah.http]
   [integrant.core :as ig]))

(defonce s3 (atom {}))

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
    (reset! s3 client)
    client))

(defmethod ig/suspend-key! ::s3
  [_ _])

(defmethod ig/resume-key ::s3
  [_ _ _ _])

(defmethod ig/halt-key! ::s3
  [_ s3]
  (aws/stop s3))

(comment
  (aws/doc @s3 :PutObject)
  (with-open [i (clojure.java.io/input-stream "/home/jarrett/tmp/clearpill.html")]
    (aws/invoke @s3 {:op :PutObject
                    :request {:Bucket "lotus-eaters"
                              :Key "clearpill.html"
                              :ContentType "text/html"
                              :ContentEncoding "utf-8"
                              :Body i}})))