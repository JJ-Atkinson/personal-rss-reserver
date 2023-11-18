(ns personal-rss-feed.name-utils
  (:require [clojure.string :as str]))

(defn format-audio
  ([uuid] (format-audio "" uuid))
  ([s3-prefix uuid] (str s3-prefix "audio-" uuid ".mp3")))

(defn format-transcript
  ([id] (format-transcript "" id))
  ([s3-prefix id] (str s3-prefix "transcript-" id ".txt")))

(defn extension-of 
  [original-uri]
  (subs original-uri (str/last-index-of original-uri ".")))

(defn format-video
  ([id original-uri] (format-video "" id original-uri))
  ([s3-prefix id original-uri] 
   (str s3-prefix "video-" id (extension-of original-uri))))
