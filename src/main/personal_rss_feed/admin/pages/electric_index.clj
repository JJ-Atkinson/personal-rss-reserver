(ns personal-rss-feed.admin.pages.electric-index
  (:require
   [clojure.edn :as edn]
   [contrib.assert :refer [check]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup.page :refer [html5]]
   [ring.util.response :as res]))


(defn get-modules
  [manifest-path]
  (when-let [manifest (io/resource manifest-path)]
    (let [manifest-folder (when-let [folder-name (second (rseq (str/split manifest-path #"\/")))]
                            (str "/" folder-name "/"))]
      (->> (slurp manifest)
           (edn/read-string)
           (reduce (fn [r module]
                     (assoc r
                            (keyword "hyperfiddle.client.module" (name (:name module)))
                            (str manifest-folder (:output-name module))))
                   {})))))

(comment
  (get-modules "public/js/manifest.edn"))

(defn index
  [bag]
  (html5
   [:head
    (str "<!-- {:hyperfiddle.electric-ring-adapter3/electric-user-version "
         (pr-str (:hyperfiddle.electric-ring-adapter3/electric-user-version bag))
         " } -->")
    [:meta {:charset "utf-8"}]
    [:title "Hyperfiddle"]
    [:meta
     {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:script
     {:type "text/javascript"
      :src (str "/public" (:hyperfiddle.client.module/main bag))
      :defer true ;; defer so js/document.body is non-nil
      }] 
      
    ;; llandmark: adjust the url
    [:link
     {:rel  "stylesheet"
      :href "/public/electric-main.css"}]]
   [:body]))

(defn handle-index
  [{::keys [manifest-path] :as config} req]
  (if-let [bag (merge config (get-modules (check string? manifest-path)))]
    (-> (res/response (index bag)) ; TODO cache in prod mode
        (res/content-type "text/html") ; ensure `index.html` is not cached
        (res/header "Cache-Control" "no-store")

        #_(res/header "Last-Modified" (get-in response [:headers "Last-Modified"]))
        ;; llandmark may be a problem that I had to remove last modified
    )
    (-> (res/not-found (pr-str ::missing-shadow-build-manifest)) ; can't inject js modules
        (res/content-type "text/plain"))))