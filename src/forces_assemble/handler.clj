(ns forces-assemble.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults secure-api-defaults site-defaults]]
            [ring.adapter.jetty :as jetty]
            [environ.core :refer [env]]
            [clj-http.client :as http]
            [clj-http.conn-mgr :refer [make-reusable-conn-manager shutdown-manager]]
            [forces-assemble.http-utils :refer :all]
            [forces-assemble.auth :as auth]
            [forces-assemble.config :as config]
            [forces-assemble.db :as db]
            [liberator.core :refer [defresource]]
            [liberator.dev :refer [wrap-trace]]))



;; HTTP
(def http-config-keys [:firebase-api-key])
(def http-configuration-ok? (config/configuration-ok? http-config-keys *ns*))

(def firebase-send-uri "https://fcm.googleapis.com/fcm/send")
(def debug-client-token "dHfG35KW8yA:APA91bGFFLRyvqzK6mUYK8DBQloGit9Uq3SZ0VeLq0lP80cCiPYtk1huM1Ls12zbU8nJK9Ag0NJS-3FEJ3pkbX0gMHzHvnbvEXyvIUUkg4aLYBE4rwSuJZiZC6_M-25Ozw119C2N7UE0")

(defn build-notification
  [event client]
  {:to client
   :priority "high"
   :data (or (:data event) {})
   :notification {:title (or (:title event) "")
                  :body (or (:body event) "")
                  :sound "default"}})

(defn add-event-to-channel
  [channel-id event]
  (let [cm (make-reusable-conn-manager {:threads 4 :timeout 10 :default-per-route 5})
        api-key (str "key=" (or (env :firebase-api-key) ""))
        added-event (db/add-event-to-channel channel-id event)]
    (doall (map (fn [client-token]
                  (println (str "Pushing: " client-token))
                  (println (str "Message: " (pr-str event)))
                  (http/post firebase-send-uri
                             {:content-type :json
                              :headers {"Authorization" api-key}
                              :form-params (build-notification event client-token)
                              :connection-manager cm}))
                (db/get-user-tokens-on-channel channel-id)))
    (shutdown-manager cm)
    added-event))

;; Server

(def authorization-required
  {:authorized? (fn [context]
                  (try (let [auth-user (auth/authenticate-token (get-authorization-token context))]
                         (if (nil? auth-user)
                           false
                           [true {::auth auth-user}]))
                       (catch Exception e [false {::exception e}])))
   :handle-unauthorized (fn [context]
                          (str "Authorization error: " (::exception context)))})

(def no-authorization-required {})

(def protected-resource authorization-required)

(def application-json "application/json")

(def json-producer-resource
  {:available-media-types [application-json]})

(def json-consumer-resource
  {:known-content-type? #(check-content-type % [application-json])
   :malformed? #(parse-json % ::data)})

(def json-resource (merge json-producer-resource json-consumer-resource))

(defn is-request-from-user?
  [context expected-user-id]
  (= expected-user-id (get-in context [::auth :user-id])))

(defresource user-tokens [user-id]
  (merge protected-resource json-resource)
  :allowed-methods [:put]
  :allowed? #(is-request-from-user? % user-id)  
  :put! (fn [context]
          (db/refresh-user-token user-id (:token (::data context)))))

(defresource user-channels [user-id]
  (merge protected-resource json-producer-resource)
  :allowed-methods [:get]
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

(defroutes assemble-routes
  (GET "/" [] "Hello World2")
  (GET "/hello" [] "More hellos")
  (ANY "/users/:id/token" [id] (user-tokens id))
  (ANY "/users/:id/channels" [id] (user-channels id))
  (ANY "/channels/:id/events" [id] (channel-events id))
  (ANY "/channels/:id/subscribers" [id] (channel-subscribers id))
  (ANY "/events/:id" [id] (events id))
  (ANY "/events/:id/participants" [id] (event-participants id))
  (route/not-found "Not Found"))

(def app
  (wrap-defaults assemble-routes (secure-api-defaults :proxy true)))

(def dev-app
  (wrap-trace app :header :ui))

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
