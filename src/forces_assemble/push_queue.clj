(ns forces-assemble.push-queue
  (:gen-class)
  (:require [cheshire.core :as che]
            [clojure.core.async :refer [<! chan go] :as async]
            [clojure.math.numeric-tower :as math]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [forces-assemble
             [config :as config]
             [context :refer [*request-id* bind]]
             [db :as db]
             [http-utils :refer [parse-json-body]]
             [push :as push]]
            [langohr
             [basic :as lb]
             [channel :as lch]
             [consumers :as lc]
             [core :as rmq]
             [queue :as lq]]))

(def push-queue-config-keys [:cloudamqp-url])
(def push-queue-config-ok? (config/configuration-ok? push-queue-config-keys *ns*))
(def ^{:const true} default-exchange-name "")
(def message-type "push.event")
(def queue-name "push")
(def dead-letter-queue-name "dead-letter")

(defmacro go-timeout
  [timeout & statements]
  `(go (<! (async/timeout ~timeout))
       ~@statements))

(defn- get-retry-headers
  [max-retries]
  {"fa-retry-count" 0
   "fa-retry-max-count" max-retries})

(defn- get-retry-delay-seconds
  [headers]
  (math/expt 2 (get headers "fa-retry-count")))

(defn- get-next-retry-headers
  [headers]
  (let [headers (update-in (into {} headers) ["fa-retry-count"] inc)]
    (when (< (get headers "fa-retry-count") (get headers "fa-retry-max-count"))
      headers)))

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
                   :correlation-id *request-id*
                   :headers (get-retry-headers 8)
                   })
      (finally
        (rmq/close ch)
        (rmq/close conn)))))

(defn- requeue-dead-letter
  [ch {:keys [correlation-id] :as meta} ^bytes payload]
  (if-let [headers (get-next-retry-headers (:headers meta))]
    (let [delay (get-retry-delay-seconds headers)]
      (log/info (format "Retrying message in %s seconds..." delay))
      (go-timeout (* delay 1000)
                  (lb/publish ch
                              default-exchange-name
                              (.toString (get (first (get headers "x-death")) "queue"))
                              payload
                              (assoc meta :headers headers))))
    (log/info "Retry limit reached")))

(defn- message-handler
  [ch {:keys [content-type delivery-tag type correlation-id] :as meta} ^bytes payload]
  (bind {:request-id correlation-id}
    (log/info (format "Received a message: %s, delivery tag: %d, content type: %s, type: %s"
                      (String. payload "UTF-8")
                      delivery-tag
                      content-type
                      type))
    (log/info (str "Meta: " meta))
    (let [payload (parse-json-body payload)
          channel-id (:channel-id payload)
          event-id (:id payload)
          delay (:delay payload)
          event (db/get-event event-id)]
      (log/info (format "Pushing after %s s. delay" delay))
      (go-timeout (* delay 1000)
                  (if (:success? (push/push-event channel-id event))
                    (lb/ack ch delivery-tag false)
                    (lb/reject ch delivery-tag false))))))

(defn- dead-letter-handler
  [ch {:keys [delivery-tag correlation-id] :as meta} ^bytes payload]
  (bind {:request-id correlation-id}
    (log/info (str "Dead letter: " meta))
    (requeue-dead-letter ch meta payload)))

(defn- start-consumer
  [ch queue-name]
  (lq/declare ch dead-letter-queue-name {:exclusive false
                                         :auto-delete false
                                         :durable true})
  (lq/declare ch queue-name {:exclusive false
                             :auto-delete false
                             :durable true
                             :arguments {"x-dead-letter-exchange" default-exchange-name
                                         "x-dead-letter-routing-key" dead-letter-queue-name}})
  (lc/subscribe ch queue-name message-handler {:auto-ack false})
  (lc/subscribe ch dead-letter-queue-name dead-letter-handler {:auto-ack true}))

(defn -main
  [& [port]]
  (let [conn  (rmq/connect {:uri (env :cloudamqp-url)})
        ch    (lch/open conn)]
    (log/info (format "Push consumer connected. Channel id: %d" (.getChannelNumber ch)))
    (start-consumer ch queue-name)
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(do (rmq/close ch) (rmq/close conn))))))
