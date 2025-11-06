(ns build-prod
  "Build script for creating production artifacts"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.build.api :as b]
            [clojure.tools.deps :as deps]))

(defn str->json
  [s]
  (if s (json/read-str s :key-fn keyword) {}))

(defn remove-timestamp!
  [root-dir lib-name]
  (let [f (io/file root-dir "META-INF/maven" (str lib-name) "pom.properties")]
    (->> (slurp f)
         string/split-lines
         (remove #(string/starts-with? % "#"))
         (string/join "\n")
         (spit f))))

(defn- get-paths
  "Get paths from deps.edn file"
  [deps]
  (-> deps
      io/file
      deps/slurp-deps
      :paths
      (or ["src"])))

(defn- parse-compile-clj-opts
  "Transform JSON string to the expect Clojure data type (keywords, symbols, ...)"
  [opts]
  (cond-> opts
    (:ns-compile opts)
    (update :ns-compile #(mapv symbol %))

    (:sort opts)
    (update :sort keyword)

    (get-in opts [:compile-opts :elide-meta])
    (update-in [:compile-opts :elide-meta] #(mapv keyword %))

    (:filter-nses opts)
    (update :filter-nses #(mapv symbol %))

    (:use-cp-file opts)
    (update :use-cp-file keyword)))

(def class-dir "target/classes")

(defn common-compile-options
  [{:keys [lib-name version]}]
  (let [lib-name (if (qualified-symbol? (symbol lib-name))
                   (symbol lib-name)
                   (symbol lib-name lib-name))]
    {:src-dirs   (get-paths "deps.edn")
     :basis      (b/create-basis {:project "deps.edn"})
     :lib-name   lib-name
     :output-jar (format "target/%s-%s.jar"
                         (name lib-name)
                         version)}))

(defn uber!
  [opts]
  (let [{:keys [main-ns compile-clj-opts]
         :as   opts}
        (-> opts
            (update :compile-clj-opts str->json))

        {:keys [src-dirs basis output-jar]} (common-compile-options opts)]

    (b/copy-dir {:src-dirs   src-dirs
                 :target-dir class-dir})
    (b/compile-clj (cond-> {:basis      basis
                            :src-dirs   src-dirs
                            :ns-compile [(symbol main-ns)]
                            :class-dir  class-dir}
                     compile-clj-opts (merge (parse-compile-clj-opts compile-clj-opts))))

    (b/uber {:class-dir class-dir
             :uber-file output-jar
             :basis     basis
             :main      main-ns})))

(comment
  (uber! {:lib-name "dev.freeformsoftware/personal-rss-reserver"
          :version  "1.0"
          :main-ns  "personal-rss-feed.prod"}))

#_(defn jar
    [{:keys [version] :as opts}]
    (let [{:keys [src-dirs basis lib-name output-jar]}
          (common-compile-options opts)]
      (b/write-pom {:class-dir class-dir
                    :lib       lib-name
                    :version   version
                    :basis     basis
                    :src-dirs  src-dirs})
      (b/copy-dir {:src-dirs   src-dirs
                   :target-dir class-dir})
      (remove-timestamp! class-dir lib-name)
      (b/jar {:class-dir class-dir
              :jar-file  output-jar})))
