(ns glimmer-cui.test-runner
  (:require [clojure.test :as t]
            glimmer-cui.core-test))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'glimmer-cui.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
