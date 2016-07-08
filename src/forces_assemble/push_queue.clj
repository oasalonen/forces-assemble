(ns forces-assemble.push-queue
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as che]
            [environ.core :refer [env]]
            [forces-assemble.config :as config]
            [forces-assemble.context :refer [bind *request-id*]]
            [forces-assemble.db :as db]
            [forces-assemble.http-utils :refer [parse-json-body]]
            [forces-assemble.logging :as logging]
            [forces-assemble.push :as push]
            [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]))

(def push-queue-config-keys [:cloudamqp-url])
(def push-queue-config-ok? (config/configuration-ok? push-queue-config-keys *ns*))
(def ^{:const true} default-exchange-name "")
(def message-type "push.event")
(def queue-name "forces-assemble.push")

(defn enqueue
  [channel-id event-id delay]
  (let [conn (rmq/connect {:uri (env :cloudamqp-url)})
        ch (lch/open conn)]
    (try
      (log/info (str "Queueing event " event-id))
      (lb/publish ch
                  default-exchange-name
                  queue-name
                  (che/generate-string {:channel-id channel-id
                                        :id event-id
                                        :delay delay})
                  {:content-type "application/json"
                   :type message-type
                   :correlation-id *request-id*})
      (finally
        (rmq/close ch)
        (rmq/close conn)))))

(defn- message-handler
  [ch {:keys [content-type delivery-tag type correlation-id] :as meta} ^bytes payload]
  (bind {:request-id correlation-id}
    (log/info (format "Received a message: %s, delivery tag: %d, content type: %s, type: %s"
                      (String. payload "UTF-8")
                      delivery-tag
                      content-type
                      type))
    ;  (log/info (str "Meta: " meta))
    (let [payload (parse-json-body payload)
          channel-id (:channel-id payload)
          event-id (:id payload)
          delay (:delay payload)
          event (db/get-event event-id)]
      (push/push-event channel-id event delay))))

(defn- start-consumer
  [ch queue-name]
  (lq/declare ch queue-name {:exclusive false :auto-delete true})
  (lc/subscribe ch queue-name message-handler {:auto-ack true}))

(defn -main
  [& [port]]
  (let [conn  (rmq/connect {:uri (env :cloudamqp-url)})
        ch    (lch/open conn)]
    (log/info (format "Push consumer connected. Channel id: %d" (.getChannelNumber ch)))
    (start-consumer ch queue-name)
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(do (rmq/close ch) (rmq/close conn))))))
