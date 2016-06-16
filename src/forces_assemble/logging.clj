(ns forces-assemble.logging
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as log-impl]
            [ring.logger :as ring-log]
            [ring.logger.protocols :as ring-log-protocols]
            [clojure.string :as cstr]
            [forces-assemble.context :refer [request-id]]))

(def custom-logging-factory
  (reify log-impl/LoggerFactory
    (name [factory] "forces-assemble-logger-factory")
    (get-logger [factory logger-ns]
      (reify log-impl/Logger
        (enabled?
          [logger level]
          true)
        (write!
          [logger level throwable message]
          (println (str (cstr/upper-case (name level)) ": [" request-id "] " message)))))))

(alter-var-root (var log/*logger-factory*)
                (constantly custom-logging-factory))

(def custom-ring-logger
  (reify ring-log-protocols/Logger
    (add-extra-middleware [_ handler] handler)
    (log [_ level throwable message]
      (case level
        :trace nil
        :debug nil
        ;else
        (log/info message)))))

(defn wrap-ring-logger
  [handler]
  (ring-log/wrap-with-logger handler {:logger custom-ring-logger
                                      :printer :no-color
                                      :timing false}))
