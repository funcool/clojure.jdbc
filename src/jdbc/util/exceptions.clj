(ns jdbc.util.exceptions
  "Some util functions.")

(defn raise-exc
  "Given a throwable, try rethrow less obscure exception."
  [^Throwable exc]
  (if (instance? java.sql.SQLException exc)
    (throw exc)
    (let [cause (.getCause exc)]
      (if (and (instance? RuntimeException exc) cause)
        (raise-exc exc)
        (throw exc)))))

(defmacro with-exception
  [& body]
  `(try
     (do ~@body)
     (catch Throwable t#
       (raise-exc t#))))


