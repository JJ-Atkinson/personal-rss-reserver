(ns personal-rss-feed.playwright.wally-utils
  (:require
   [personal-rss-feed.playwright.wally-state :as wally-state]
   [wally.main :as w])
  (:import
   (com.microsoft.playwright BrowserType$LaunchOptions)
   (com.microsoft.playwright.options Cookie)
   (java.nio.file Paths)))

(defn fresh-browser
  "This is a very expensive call. Avoid using it.

   :headless?            If true, the browser will be headless.
   :type                 The type of browser to launch. One of #{:chromium :firefox :webkit}
   :between-action-delay The ms delay between browser actions. Defaults to 0. See LaunchOptions.setSlowMo.
   :devtools?            Start chromium devtools?"
  [{:keys [headless? type between-action-delay devtools? exe-path]
    :or {headless? true
         type :chromium
         between-action-delay 0
         devtools? false
         exe-path (some-> (System/getenv "CHROME_LOCATION")
                    (Paths/get (into-array String [])))}}]
  (let [pw @wally-state/playwright-instance
        browser (case type
                  :chromium (.chromium pw)
                  :firefox (.firefox pw)
                  :webkit (.webkit pw))]
    (.launch browser (-> (BrowserType$LaunchOptions.)
                       (.setHeadless headless?)
                       (.setSlowMo between-action-delay)
                       (.setDevtools devtools?)
                       (cond->
                         (and exe-path (not= exe-path :default)) 
                         (.setExecutablePath exe-path))))))

(defn cached-browser
  [key fallback-create-fn]
  (fn [] (-> (swap! wally-state/name->playwright-object
               #(update % key (fn [browser] (or (when (some-> browser (.isConnected)) browser)
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
    :or {browser default-browser
         default-timeout 5000}}]
  (let [context (.newContext browser)]
    (.setDefaultTimeout context default-timeout)
    context))

(defn sign-in-context!
  [context user-id]
  (.clearCookies context)
  (.clearPermissions context)
  (when user-id
    (.addCookies context [(-> (Cookie. "access-token" (str user-id))
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
  (let [ctx (or browser-context
              (fresh-browser-context {:browser (if debug
                                                 (debug-browser debug)
                                                 (default-browser))}))]
    {:page (.newPage ctx)
     :browser-context ctx}))

(defmacro with-page
  "Accepts a map of :page and :browser-context, see `fresh-page`"
  [page-desc & body]
  `(let [page-desc# ~page-desc]
     (with-open [page# (:page page-desc#)
                 browser-context# (:browser-context page-desc#)]
       (w/with-page page# ~@body))))

(def navigate w/navigate)
