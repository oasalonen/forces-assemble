(ns forces-assemble.handler
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as cstr]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults secure-api-defaults site-defaults]]
            [ring.adapter.jetty :as jetty]
            [ring.logger :as logger]
            [ring.logger.protocols :as logger-protocols]
            [environ.core :refer [env]]
            [clj-http.client :as http]
            [clj-http.conn-mgr :refer [make-reusable-conn-manager shutdown-manager]]
            [forces-assemble.http-utils :refer :all]
            [forces-assemble.auth :as auth]
            [forces-assemble.config :as config]
            [forces-assemble.db :as db]
            [forces-assemble.logging :as logging]
            [forces-assemble.context :refer [request-id]]
            [liberator.core :refer [defresource]]
            [liberator.dev :refer [wrap-trace]]
            [java-time :as jt]
            [java-time.format :as jt-format]
            [clj-uuid :as uuid]
            [overtone.at-at :as at]))

;; HTTP
(def api-uri-prefix "/api/v1")
(def http-config-keys [:firebase-api-key
                       :papertrail-api-token])
(def http-configuration-ok? (config/configuration-ok? http-config-keys *ns*))

(def firebase-send-uri "https://fcm.googleapis.com/fcm/send")
(def debug-client-token "dHfG35KW8yA:APA91bGFFLRyvqzK6mUYK8DBQloGit9Uq3SZ0VeLq0lP80cCiPYtk1huM1Ls12zbU8nJK9Ag0NJS-3FEJ3pkbX0gMHzHvnbvEXyvIUUkg4aLYBE4rwSuJZiZC6_M-25Ozw119C2N7UE0")
(def papertrail-events-uri "https://papertrailapp.com/api/v1/events/search.json")

(defonce push-thread-pool (at/mk-pool))

(defn api
  [uri]
  (str api-uri-prefix uri))

(defn build-notification
  [event client]
  {:to client
   :priority "high"
   :collapse_key (:id event)
   :data (or (:data event) {})
   :notification {:title (or (:title event) "")
                  :body (or (:body event) "")
                  :sound "default"}})

(defn push-event
  [channel-id event]
  (let [cm (make-reusable-conn-manager {:threads 4 :timeout 10 :default-per-route 5})
        api-key (str "key=" (or (env :firebase-api-key) ""))]
    (doall (map (fn [client-token]
                  (log/info (str "Pushing: " client-token))
                  (log/info (str "Message: " (pr-str event)))
                  (http/post firebase-send-uri
                             {:content-type :json
                              :headers {"Authorization" api-key}
                              :form-params (build-notification event client-token)
                              :connection-manager cm}))
                (db/get-user-notification-tokens-on-channel channel-id)))
    (shutdown-manager cm)))

(defn add-event-to-channel
  [channel-id event]
  (let [push-delay (:delay event)
        added-event (db/add-event-to-channel channel-id event)]
    (if push-delay
      (at/at (+ (* 1000 push-delay) (at/now))
             #((log/info (str "Pushing after " push-delay " s. delay"))
               (push-event channel-id added-event))
             push-thread-pool)
      (push-event channel-id added-event))
    added-event))

(defn get-server-logs
  [& {:keys [query min-time min-id] :or {query nil min-time nil min-id nil}}]
  (let [query (str "program:(app/web.1)" (when query (str " " query)))
        min-time (or min-time (.getEpochSecond (jt/minus (jt/instant) (jt/hours 1))))]
    (log/info (str "Querying server logs for \"" query "\" occurring after " min-time " with min-id " min-id))
    (http/get papertrail-events-uri
              {:headers {"X-Papertrail-Token" (env :papertrail-api-token)}
               :query-params (merge {"tail" false
                                     "q" query}
                                    (if min-id
                                      {"min_id" min-id}
                                      {"min_time" min-time}))})))

(defn logs-uri
  [min-id query]
  (api (str "/logs?min_id=" min-id (when query (str "&query=" query)))))

(defn logs-to-html
  [logs-body query]
  (let [time-formatter (jt-format/predefined-formatters "iso-offset-date-time")
        log-event-format (slurp (io/resource "log-event-format.html"))
        log-page-format (slurp (io/resource "log-page-format.html"))
        body (parse-json-body logs-body)
        max-id (:max_id body)
        events (apply str (map (fn [event]
                                 (let [message (:message event)
                                       id (second (re-find #"^(\[[a-fA-F\d-]*\])" message))
                                       error (second (re-find #"([Ee]rror|[Ee]xception)" message))
                                       status (second (re-find #"Status: (\d{3})" message))]
                                   (format log-event-format
                                           (jt/format (jt/instant time-formatter (:received_at event)))
                                           (or id "")
                                           (cond
                                               (some? error) "error"
                                               (and status (>= (Integer. status) 400)) "error"
                                               :else "ok")
                                           (if id (cstr/replace-first message id "") message))))
                               (:events body)))]
    (format log-page-format
            events
            (logs-uri max-id query))))

;; Server

(def application-json "application/json")
(def text-html "text/html")

(def authorization-required
  {:authorized? (fn [context]
                  (try (let [auth-user (auth/authenticate-token (get-authorization-token context))]
                         (if (nil? auth-user)
                           false
                           [true {::auth auth-user}]))
                       (catch Exception e
                         (log/warn e "Authorization error")
                         [false {::exception e}])))
   :handle-unauthorized (fn [context]
                          (str "Authorization error: " (::exception context)))})

(def no-authorization-required {})

(def protected-resource authorization-required)

(def json-producer-resource
  {:available-media-types [application-json]})

(def json-consumer-resource
  {:known-content-type? #(check-content-type % [application-json])
   :malformed? #(parse-json % ::data)})

(def json-resource (merge json-producer-resource json-consumer-resource))

(defn is-request-from-user?
  [context expected-user-id]
  (= expected-user-id (get-in context [::auth :user-id])))

(defresource user-notification-token [user-id]
  (merge protected-resource json-resource)
  :allowed-methods [:put]
  :allowed? #(is-request-from-user? % user-id)  
  :put! (fn [context]
          (db/refresh-user-notification-token user-id (:notification-token (::data context)))))

(defresource user-channels [user-id]
  (merge protected-resource json-producer-resource)
  :allowed-methods [:get]
  :allowed? #(is-request-from-user? % user-id)
  :handle-ok (fn [context]
               (db/get-subscribed-channels user-id)))

(defresource channel-events [channel-id]
  (merge protected-resource json-resource)
  :allowed-methods [:post]
  :post! (fn [context]
           (let [event-with-author (assoc (::data context)
                                          :author
                                          {:user-id (get-in context [::auth :user-id])})
                 event (add-event-to-channel channel-id event-with-author)
                 event-id (:id event)]
            {:location (build-entry-url context
                                        "/events"
                                        event-id)
             ::created {:id event-id}}))
  :handle-created #(::created %))

(defresource channel-subscribers [channel-id]
  (merge protected-resource json-resource)
  :allowed-methods [:post]
  :allowed? #(is-request-from-user? % (get-in % [::data :user-id]))
  :post! (fn [context]
           (db/subscribe-to-channel channel-id (get-in context [::data :user-id]))))

(defresource channel-subscriber-user [channel-id user-id]
  protected-resource
  :allowed-methods [:delete]
  :available-media-types ["text/plain"]
  :allowed? #(is-request-from-user? % user-id)
  :delete! (fn [context] (db/unsubscribe-from-channel channel-id user-id)))

(defresource events [event-id]
  (merge protected-resource json-producer-resource)
  :allowed-methods [:get]
  :exists? (fn [context]
             (if-let [event (db/get-event event-id)]
               {::data event}
               false))
  :handle-ok #(::data %))

(defresource event-participants [event-id]
  (merge protected-resource json-resource)
  :allowed-methods [:post]
  :allowed? #(is-request-from-user? % (get-in % [::data :user-id]))
  :post! (fn [context]
           (db/add-event-participant event-id (::auth context))))

(defresource server-logs []
  :available-media-types [application-json text-html]
  :allowed-methods [:get]
  :handle-ok (fn [context]
               (let [query (get-in context [:request :params :query])
                     min-time (get-in context [:request :params :min_time])
                     min-id (get-in context [:request :params :min_id])
                     events (:body (get-server-logs :query query :min-time min-time :min-id min-id))]
                 (condp = (get-in context [:representation :media-type])
                   text-html :>> (fn [_] (logs-to-html events query))
                   application-json :>> (fn [_] (identity events))
                   nil))))

(defroutes assemble-routes
  (GET "/" [] (io/resource "index.html"))
  (GET "/api.js" [] (io/resource "api.js"))
  (GET "/custom-account.html" [] (io/resource "custom-account.html"))
  (GET "/google-account.html" [] (io/resource "google-account.html"))
  (GET "/api.html" [] (io/resource "api.html"))
  (GET "/firebase.html" [] (io/resource "firebase.html"))
  (GET "/style.css" [] (io/resource "style.css"))
  (ANY (api "/users/:id/notification-token") [id] (user-notification-token id))
  (ANY (api "/users/:id/channels") [id] (user-channels id))
  (ANY (api "/channels/:id/events") [id] (channel-events id))
  (ANY (api "/channels/:id/subscribers") [id] (channel-subscribers id))
  (ANY (api "/channels/:channel-id/subscribers/:user-id") [channel-id user-id] (channel-subscriber-user channel-id user-id))
  (ANY (api "/events/:id") [id] (events id))
  (ANY (api "/events/:id/participants") [id] (event-participants id))
  (ANY (api "/logs") [] (server-logs))
  (route/not-found "Not Found"))

(defn wrap-request-id
  [handler]
  (fn [request]
    (binding [request-id (or (get-in request [:headers "x-request-id"])
                             (str (uuid/v1)))]
      (handler request))))

(def app
  (wrap-defaults assemble-routes (assoc secure-api-defaults :proxy true)))

(def logged-app
  (wrap-request-id (logging/wrap-ring-logger app)))

(def dev-app
  (wrap-trace logged-app :header :ui))

(defn def-server []
  (def server
    (do (config/configuration-ok? [:jetty-keystore-password] *ns*)
        (jetty/run-jetty #'dev-app
                         {:port 8000
                          :join? false
                          :ssl? true
                          :ssl-port 8443
                          :keystore (str (env :home) "/jetty.keystore")
                          :key-password (env :jetty-keystore-password)}))))

(defn restart-server []
  (.stop server)
  (def-server)
  (.start server))
