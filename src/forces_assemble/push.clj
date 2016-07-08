(ns forces-assemble.push
  (:require [clojure.string :as cstr]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [forces-assemble.config :as config]
            [forces-assemble.context :refer [fn-rebind *request-id*]]
            [forces-assemble.db :as db]
            [forces-assemble.http-utils :refer :all]
            [clj-http.client :as http]
            [clj-http.conn-mgr :refer [make-reusable-conn-manager shutdown-manager]]
            [chime :refer [chime-at]]
            [clj-time.core :as t]))

(def push-config-keys [:firebase-api-key])
(def push-config-ok? (config/configuration-ok? push-config-keys *ns*))
(def firebase-send-uri "https://fcm.googleapis.com/fcm/send")
(def debug-client-token "dHfG35KW8yA:APA91bGFFLRyvqzK6mUYK8DBQloGit9Uq3SZ0VeLq0lP80cCiPYtk1huM1Ls12zbU8nJK9Ag0NJS-3FEJ3pkbX0gMHzHvnbvEXyvIUUkg4aLYBE4rwSuJZiZC6_M-25Ozw119C2N7UE0")

(defn- build-notification
  [event client]
  (let [title (:title event)
        body (:body event)
        notification {:to client
                      :priority "high"
                      :collapse_key (:id event)
                      :data (or (:data event) {})
                      :notification {:title (or title "")
                                     :body (or body "")
                                     :sound "default"}}]
    (if (every? cstr/blank? [title body])
      (dissoc notification :notification)
      notification)))

(defn- handle-push-response
  [response]
  (let [body (parse-json-body (:body response))]
    (cond
      (> (:failure body) 0) (log/error (str "Push failed: "
                                            (:status response) " "
                                            (-> body :results first :error)) )
      (> (:success body) 0) (log/info "Successfully pushed event")
      :else (log/info "No events pushed"))))

(defn- push-event-over-http
  [channel-id event]
  (let [cm (make-reusable-conn-manager {:threads 4 :timeout 10 :default-per-route 5})
        api-key (str "key=" (or (env :firebase-api-key) ""))]
    (doall (map (fn [client-token]
                  (log/info (str "Pushing to: " client-token))
                  (log/info (str "Message: " (pr-str event)))
                  (try
                    (handle-push-response (http/post firebase-send-uri
                                                     {:content-type :json
                                                      :headers {"Authorization" api-key}
                                                      :form-params (build-notification event client-token)
                                                      :connection-manager cm}))
                    (catch Exception e
                      (log/error e "Push exception"))))
                (db/get-user-notification-tokens-on-channel channel-id)))
    (log/info "Finished pushing events")
    (shutdown-manager cm)))

(defn push-event
  [channel-id event & [delay]]
  (if delay
    (do
      (log/info (str "Pushing after " delay " s. delay"))
      (chime-at [(-> delay t/seconds t/from-now)]
                (fn-rebind [time]
                 (push-event-over-http channel-id event))))
    (push-event-over-http channel-id event)))
