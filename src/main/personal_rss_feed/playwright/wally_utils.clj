(ns personal-rss-feed.playwright.wally-utils
  (:require
   [personal-rss-feed.playwright.wally-state :as wally-state]
   [wally.main :as w]
   [clojure.java.io :as io])
  (:import
   (com.microsoft.playwright Browser BrowserType$LaunchOptions)
   (com.microsoft.playwright.options Cookie)
   (java.nio.file Paths)))

(defn find-nix-browser-executable
  "Finds the browser executable in the Nix-provided PLAYWRIGHT_BROWSERS_PATH.
   Falls back to nil if not using Nix or if the browser doesn't exist.

   :type - One of #{:chromium :firefox :webkit}"
  [type]
  (when-let [browsers-path (System/getenv "PLAYWRIGHT_BROWSERS_PATH")]
    (let [browser-name (case type
                         :chromium "chromium"
                         :firefox "firefox"
                         :webkit "webkit")
          ;; Find the most recent version directory (e.g., chromium-1181)
          browser-dir (io/file browsers-path)
          matching-dirs (->> (.listFiles browser-dir)
                             (filter #(.isDirectory %))
                             (filter #(.startsWith (.getName %) (str browser-name "-")))
                             (sort-by #(.getName %) #(compare %2 %1))) ;; sort descending
          latest-dir (first matching-dirs)]
      (when latest-dir
        (let [exec-path (case type
                          :chromium (io/file latest-dir "chrome-linux" "chrome")
                          :firefox (io/file latest-dir "firefox" "firefox")
                          :webkit (io/file latest-dir "minibrowser-gtk" "pw_run.sh"))]
          (when (.exists exec-path)
            (.getAbsolutePath exec-path)))))))

(defn fresh-browser
  "This is a very expensive call. Avoid using it.

   :headless?            If true, the browser will be headless.
   :type                 The type of browser to launch. One of #{:chromium :firefox :webkit}
   :between-action-delay The ms delay between browser actions. Defaults to 0. See LaunchOptions.setSlowMo.
   :devtools?            Start chromium devtools?
   :executable-path      Override the browser executable path. If not provided, will attempt
                        to find the Nix-provided browser automatically."
  [{:keys [headless? type between-action-delay devtools? executable-path]
    :or {headless? true
         type :chromium
         between-action-delay 0
         devtools? false}}]
  (let [pw @wally-state/playwright-instance
        browser (case type
                  :chromium (.chromium pw)
                  :firefox (.firefox pw)
                  :webkit (.webkit pw))
        exec-path (or executable-path (find-nix-browser-executable type))
        launch-opts (-> (BrowserType$LaunchOptions.)
                        (.setHeadless headless?)
                        (.setSlowMo between-action-delay)
                        (.setDevtools devtools?))]
    ;; Only set executable path if we found one (allows falling back to default behavior)
    (when exec-path
      (.setExecutablePath launch-opts (Paths/get exec-path (into-array String []))))
    (.launch browser launch-opts)))

(defn cached-browser
  [key fallback-create-fn]
  (fn []
    (-> (swap! wally-state/name->playwright-object
               #(update %
                        key
                        (fn [browser]
                          (or (when (some-> browser
                                            (.isConnected))
                                browser)
                              (fallback-create-fn)))))
        (get key))))

(def default-browser
  (cached-browser ::default-browser #(fresh-browser {})))

(defn debug-browser
  [opts]
  (let [opts (merge {:headless? false
                     :between-action-delay 75
                     :devtools? true}
                    opts)]
    ((cached-browser [::debug-browser opts] #(fresh-browser opts)))))

(defn fresh-browser-context
  "Semi expensive call. Not usually required, since you can clear & sign in the content of a context with `sign-in-context!`.
   Mostly useful if you wanted to test multiple users signed in at once."
  [{:keys [browser
           default-timeout]
    :or {default-timeout 5000}}]
  (let [context (.newContext ^Browser (or browser (default-browser)))]
    (.setDefaultTimeout context default-timeout)
    context))

(defn sign-in-context!
  [context user-id]
  (.clearCookies context)
  (.clearPermissions context)
  (when user-id
    (.addCookies context
                 [(-> (Cookie. "access-token" (str user-id))
                      (.setDomain "localhost")
                      (.setPath "/"))])))

(defn fresh-page
  "Create a playwright page, for use with `w/with-page`.

   :browser-context If supplied, this context will be used. 
   :debug           A map which, if present, builds a browser with good defaults for viewing the test live.
                    Any options are overrides to `fresh-browser`.

   If no browser context is supplied, remaining args are passed to `fresh-browser-context`

   Returns:
   :page            The page object
                    https://www.javadoc.io/static/com.microsoft.playwright/playwright/1.33.0/com/microsoft/playwright/Page.html
   :browser-context The browser context object 
                    https://www.javadoc.io/doc/com.microsoft.playwright/playwright/latest/com/microsoft/playwright/BrowserContext.html"
  [{:keys [browser-context debug]}]
  (let [ctx (or (and (not debug) browser-context)
                (fresh-browser-context {:browser (if debug
                                                   (debug-browser debug)
                                                   (default-browser))}))]
    {:page (.newPage ctx)
     :browser-context ctx}))

(defmacro with-page
  "Accepts a map of :page and :browser-context, see `fresh-page`"
  [page-desc & body]
  `(let [page-desc# ~page-desc]
     (if (:autoclose-browser-context? page-desc# true)
       (with-open [page# (:page page-desc#)
                   browser-context# (:browser-context page-desc#)]
         (w/with-page page# ~@body))
       (with-open [page# (:page page-desc#)]
         (w/with-page page# ~@body)))))

(def navigate w/navigate)
