(ns forces-assemble.logs-api
  (:require [clojure.java.io :as io]
            [clojure.string :as cstr]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [environ.core :refer [env]]
            [forces-assemble.http-utils :refer :all]
            [forces-assemble.config :as config]
            [java-time :as jt]
            [java-time.format :as jt-format]))

(def logs-config-keys [:papertrail-api-token])
(def logs-config-ok? (config/configuration-ok? logs-config-keys *ns*))
(def papertrail-events-uri "https://papertrailapp.com/api/v1/events/search.json")

(defn- logs-uri
  [min-id query]
  (config/api (str "/logs?min_id=" min-id (when query (str "&query=" query)))))

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
