(ns personal-rss-feed.playwright.selectors
  (:require
   [wally.main :as w]
   [wally.selectors])
  (:import
    (java.lang.reflect Method)
    (com.microsoft.playwright.impl LocatorUtils)
    (com.microsoft.playwright Locator
                              Locator$GetByTextOptions
                              Locator$GetByLabelOptions
                              ElementHandle
                              Locator$GetByTitleOptions)))

(def
  ^{:private true
    :doc
    "Regain access to the standard selectors hidden by object methods in Playwright. These methods are helpers
          used by `playwright.impl.PageImpl` to implement the standard selectors behind java functions in a non composable
          way."}
  locator-utils-methods
  (->> (.getDeclaredMethods LocatorUtils)
       (seq)
       (into {}
             (map (juxt #(.getName %)
                        (fn [m]
                          (.setAccessible ^Method m true)
                          m))))))

(defn- invoke-locator-utils-method
  "Regain access to the standard selectors hidden by object methods in Playwright"
  [method-name & args]
  (.invoke ^Method (get locator-utils-methods method-name)
           nil
           (into-array Object args)))

(defn text
  "Selects an element by text content. See https://playwright.dev/java/docs/locators#locate-by-text
   Returns a selector"
  [text & {:keys [exact] :or {exact false}}]
  (invoke-locator-utils-method "getByTextSelector" text (.setExact (Locator$GetByTextOptions.) exact)))

(defn title
  "Selects an element by title attribute. See https://playwright.dev/java/docs/api/class-page#page-get-by-title
   Returns a selector"
  [text & {:keys [exact] :or {exact false}}]
  (invoke-locator-utils-method "getByTitleSelector" text (.setExact (Locator$GetByTitleOptions.) exact)))

(defn label
  "Select a form element by the text of its label. See https://playwright.dev/java/docs/locators#locate-by-label
   Returns a selector"
  [text & {:keys [exact] :or {exact false}}]
  (invoke-locator-utils-method "getByLabelSelector" text (.setExact (Locator$GetByLabelOptions.) exact)))

(defn test-id
  "Select an element by its `data-testid` attribute. See https://playwright.dev/java/docs/locators#locate-by-test-id
   Returns a selector"
  [test-id]
  (invoke-locator-utils-method "getByTestIdSelector" test-id))

(defn query-1
  "Query that returns just one locator. If more than one match is found, an exception is thrown. If none is found,
   the query is retried until the timeout is reached (usually 5s)

   https://www.javadoc.io/static/com.microsoft.playwright/playwright/1.33.0/com/microsoft/playwright/Locator.html"
  ^Locator
  [q]
  (w/-query q))

(defn query
  "Returns a seqable locator for the given selector. If none is found, the query is retried until the timeout is 
   reached (usually 5s), and an exception is thrown.

   https://www.javadoc.io/static/com.microsoft.playwright/playwright/1.33.0/com/microsoft/playwright/Locator.html"
  [q]
  (w/query q))

(defn query-1-now
  "Returns the first element found by the given selector immediately. Unlike `query`, this does not throw if more than one
   match is found. Nil if none are found.

  https://www.javadoc.io/static/com.microsoft.playwright/playwright/1.33.0/com/microsoft/playwright/ElementHandle.html"
  ^ElementHandle
  [q]
  (.querySelector w/*page* (w/query->selector q)))

(defn query-now
  "Returns all the elements found by the given selector. Nil if none are found.

  https://www.javadoc.io/static/com.microsoft.playwright/playwright/1.33.0/com/microsoft/playwright/ElementHandle.html"
  [q]
  (seq (.querySelectorAll w/*page* (w/query->selector q))))


(comment
  (assoc (assoc {} :a 1 :b 2)
         :k
         3)

  (let [a 1
        b 2]
    (assoc {:a 1}
           :b
           b)))
