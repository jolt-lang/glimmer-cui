(ns glimmer-cui.dashboard
  "Generated components with array props and struct out-parameters, fed by a
  background thread. Run: jolt -M:examples -m glimmer-cui.dashboard

  The ticker thread swap!s a ratom once a second while the window waits for
  input, so the live chart only moves if cui_app_wake wakes the loop."
  (:require [glimmer.ratom :as r]
            [glimmer.core :as g]
            [glimmer-cui.core]))

(defonce state
  (r/atom {:tab 0
           :range :week
           :days [true true true true true false false]
           :due {:year 2026 :month 11 :day 23}
           :accent {:r 99 :g 102 :b 241 :a 255}
           :load [3 5 4 6 5 7 6 8 7 9]
           :ticks 0}))

(defn- start-ticker!
  "Push a new load sample every second from another thread."
  []
  (doto (Thread. (fn []
                   (loop []
                     (Thread/sleep 1000)
                     (swap! state (fn [s] (-> s
                                              (update :ticks inc)
                                              (update :load #(conj (vec (rest %)) (+ 2 (rand-int 9)))))))
                     (recur))))
    (.setDaemon true)
    (.start)))

(defn overview []
  (let [{:keys [load ticks accent]} @state]
    [:column {:gap 12}
     [:row {:gap 12}
      [:metric {:label "Samples" :value (str ticks)}]
      [:metric {:label "Latest" :value (str (peek load))}]]
     [:line-chart {:series [{:name "Load" :values load :color accent}] :height 180 :area true}]]))

(defn schedule []
  (let [{:keys [days due range]} @state]
    [:column {:gap 12}
     [:segmented {:items ["Day" "Week" "Month"] :index ({:day 0 :week 1 :month 2} range)
                  :on-change #(swap! state assoc :range ([:day :week :month] %))}]
     [:toggle-group {:labels ["Mo" "Tu" "We" "Th" "Fr" "Sa" "Su"] :values days
                     :on-change #(swap! state assoc :days %)}]
     [:date-picker {:value due :on-change #(swap! state assoc :due %)}]
     [:text {:text (str "Due " (:year due) "-" (:month due) "-" (:day due) ", "
                        (count (filter true? days)) " working days")}]]))

(defn appearance []
  [:column {:gap 12}
   [:prop-color {:label "Chart color" :value (:accent @state) :on-change #(swap! state assoc :accent %)}]])

(defn app []
  (let [tab (:tab @state)]
    [:column {:pad 24 :gap 16}
     [:heading {:text "Dashboard" :level 2}]
     [:tabs {:items ["Overview" "Schedule" "Appearance"] :index tab
             :on-change #(swap! state assoc :tab %)}]
     (case tab
       0 [overview]
       1 [schedule]
       [appearance])]))

(defn -main [& _]
  (start-ticker!)
  (g/run app :title "Dashboard" :width 560 :height 480))
