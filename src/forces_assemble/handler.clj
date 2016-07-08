(ns forces-assemble.handler
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults secure-api-defaults site-defaults]]
            [ring.adapter.jetty :as jetty]
            [ring.logger :as logger]
            [ring.logger.protocols :as logger-protocols]
            [environ.core :refer [env]]
            [forces-assemble.http-utils :refer :all]
            [forces-assemble.auth :as auth]
            [forces-assemble.config :as config]
            [forces-assemble.db :as db]
            [forces-assemble.logging :as logging]
            [forces-assemble.logs-api :as logs-api]
            [forces-assemble.context :refer [bind *request-id*]]
            [forces-assemble.push-queue :as push-queue]
            [liberator.core :refer [defresource]]
            [liberator.dev :refer [wrap-trace]]
            [clj-uuid :as uuid]))

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

(def protected-resource no-authorization-required)

(def json-producer-resource
  {:available-media-types [application-json]})

(def json-consumer-resource
  {:known-content-type? #(check-content-type % [application-json])
   :malformed? #(parse-json % ::data)})

(def json-resource (merge json-producer-resource json-consumer-resource))

(defn is-request-from-user?
  [context expected-user-id]
  ;(= expected-user-id (get-in context [::auth :user-id]))
  true)

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
                 event (db/add-event-to-channel channel-id event-with-author)
                 event-id (:id event)]
             (push-queue/enqueue channel-id
                                 event-id
                                 (:delay event-with-author))
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
           (log/info (str "Subscribing: " (::data context)))
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
                     events (:body (logs-api/get-server-logs :query query :min-time min-time :min-id min-id))]
                 (condp = (get-in context [:representation :media-type])
                   text-html :>> (fn [_] (logs-api/logs-to-html events query))
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
  (ANY (config/api "/users/:id/notification-token") [id] (user-notification-token id))
  (ANY (config/api "/users/:id/channels") [id] (user-channels id))
  (ANY (config/api "/channels/:id/events") [id] (channel-events id))
  (ANY (config/api "/channels/:id/subscribers") [id] (channel-subscribers id))
  (ANY (config/api "/channels/:channel-id/subscribers/:user-id") [channel-id user-id] (channel-subscriber-user channel-id user-id))
  (ANY (config/api "/events/:id") [id] (events id))
  (ANY (config/api "/events/:id/participants") [id] (event-participants id))
  (ANY (config/api "/logs") [] (server-logs))
  (route/not-found "Not Found"))

(defn wrap-request-id
  [handler]
  (fn [request]
    (bind {:request-id (or (get-in request [:headers "x-request-id"])
                           (str (uuid/v1)))}
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
