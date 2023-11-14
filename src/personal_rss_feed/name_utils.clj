(ns personal-rss-feed.name-utils)

(defn format-audio
  ([id] (format-audio "" id))
  ([s3-prefix id] (str s3-prefix "audio-" id ".mp3")))

(defn format-transcript
  ([id] (format-transcript "" id))
  ([s3-prefix id] (str s3-prefix "transcript-" id ".txt")))

(defn format-video
  ([id] (format-video "" id))
  ([s3-prefix id] (str s3-prefix "video-" id ".m4v")))
