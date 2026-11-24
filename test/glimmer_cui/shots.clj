(ns glimmer-cui.shots
  "Render the examples headlessly to PNGs in target/: jolt shots"
  (:require [babashka.fs :as fs]
            [glimmer-cui.core :as gc]
            [glimmer-cui.headless :as h]
            [glimmer-cui.dashboard :as dashboard]
            [glimmer-cui.hello :as hello]
            [glimmer-cui.settings :as settings]))

(defn- shot [path w hiccup & {:keys [h before] :or {h 400}}]
  (let [s (h/open {:width w :height h})]
    (try
      (h/mount! s hiccup)
      (h/step! s 40)                      ; let entrance animations settle
      (when before (before s) (h/step! s 40))
      (println "wrote" (h/screenshot! s path))
      (finally (h/close! s)))))

(defn -main [& _]
  (fs/create-dirs "target")
  (shot "target/hello.png" 420 [hello/app] :h 300)
  (shot "target/settings.png" 560 [settings/app] :h 560)
  (shot "target/settings-toast.png" 560 [settings/app] :h 560
        :before (fn [_] (settings/toast! "Settings saved" :success)))
  (shot "target/settings-dialog.png" 560 [settings/app] :h 560
        :before (fn [_] (swap! settings/state assoc :confirm true)))
  (shot "target/dashboard.png" 560 [dashboard/app] :h 480)
  (shot "target/dashboard-schedule.png" 560 [dashboard/app] :h 480
        :before (fn [_] (swap! dashboard/state assoc :tab 1))))
