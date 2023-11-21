(ns personal-rss-feed.name-utils
  (:require [clojure.string :as str]))

(defn format-audio
  ([uuid] (format-audio "" uuid))
  ([s3-prefix uuid] (str s3-prefix "audio-" uuid ".mp3")))

(defn format-transcript
  ([uuid] (format-transcript "" uuid))
  ([s3-prefix uuid] (str s3-prefix "transcript-" uuid ".txt")))

(defn extension-of
  [original-uri]
  (as-> original-uri $
    (subs $ (str/last-index-of $ "."))
    (if (str/includes? $ "?")
      (subs $ 0 (str/index-of $ "?"))
      $)))

(defn format-video
  ([uuid original-uri] (format-video "" uuid original-uri))
  ([s3-prefix uuid original-uri]
   (str s3-prefix "video-" uuid (extension-of original-uri))))
