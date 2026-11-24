(ns glimmer-cui.test-runner
  (:require [clojure.test :as t]
            glimmer-cui.core-test
            glimmer-cui.generated-test
            glimmer-cui.protocols-test))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'glimmer-cui.core-test 'glimmer-cui.generated-test
                                          'glimmer-cui.protocols-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
