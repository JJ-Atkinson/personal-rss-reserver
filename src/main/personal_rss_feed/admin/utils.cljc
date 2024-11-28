(ns personal-rss-feed.admin.utils 
  (:import [java.lang Character]))

#?(:clj
   (defn contains-as-subseq?
     [^String candidate ^String search-term]
     (let [can-len  (.length candidate)
           sear-len (.length search-term)]
       (loop [candidate-index 0
              search-index    0]
         (cond (>= search-index sear-len) true
               (>= candidate-index can-len) false
               (= (Character/toLowerCase (.charAt candidate candidate-index))
                  (Character/toLowerCase (.charAt search-term search-index)))
               (recur (inc candidate-index) (inc search-index))

               :else (recur (inc candidate-index) search-index))))))