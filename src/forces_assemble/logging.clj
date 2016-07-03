(ns forces-assemble.logging
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as log-impl]
            [ring.logger :as ring-log]
            [ring.logger.protocols :as ring-log-protocols]
            [clojure.string :as cstr]
            [forces-assemble.context :refer [*request-id*]]))

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
          (let [output (str "[" *request-id* "] " message)]
            (if (or throwable (some level [:error :warn]))
              (binding [*out* *err*]
                (println (str output " - " throwable)))
              (println output))))))))

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
        (log/log level throwable message)))))

(defn wrap-ring-logger
  [handler]
  (ring-log/wrap-with-logger handler {:logger custom-ring-logger
                                      :printer :no-color
                                      :timing false}))
