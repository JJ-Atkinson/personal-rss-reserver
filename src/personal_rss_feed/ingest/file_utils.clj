(ns personal-rss-feed.ingest.file-utils
  (:require [tempfile.core :as tempfile])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn create-temp-file
  [prefix suffix]
  (.toFile (Files/createTempFile prefix suffix (make-array FileAttribute 0))))

(defmacro with-tempfile
  [& body] 
  `(tempfile/with-tempfile ~@body))
