(ns forces-assemble.context)

(def ^:dynamic *request-id* nil)

(defmacro bind
  "Bind the context to a block of statements. This should be used in the early stages of
  ring in order to initialize the context correctly for handling the current request."
  [context & statements]
  `(binding [*request-id* (:request-id ~context)]
     (do ~@statements)))

(defmacro fn-rebind
  "Rebind the context to a block of statements and return a function that wraps them.
  This should be called for any functions that are executed in a new thread scope
  (any time futures, promises or other kind of scheduling is used) because the context
  binding applies only to the thread that receives a client request."
  [args & statements]
  `(let [request-id# *request-id*]
     (fn [~@args]
       (binding [*request-id* request-id#]
         (do ~@statements)))))
