(ns personal-rss-feed.ingest.shell-utils
  (:require
   [babashka.process :as proc]))

(defn throwing-shell
  [& args]
  (let [has-opts? (map? (first args))
        opts      (merge {:out :string
                          :err :string}
                         (when has-opts? (first args)))
        args      (cond-> args has-opts? (rest))
        result    @(apply proc/process opts args)]
    (if (not (zero? (:exit result)))
      (throw (ex-info "Shell threw an error!"
                      {:args       args
                       :opts       opts
                       :error      (:err result)
                       :error-code (:exit result)
                       :out        (:out result)}))
      (:out result))))

(comment
  (throwing-shell {:some :opt} "ls")
  (throwing-shell "ls -lsdfasdfwergweb1b3g"))
