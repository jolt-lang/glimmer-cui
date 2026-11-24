(ns glimmer-cui.hello
  "cui-jolt's hello example as glimmer components. Run: jolt -M:examples -m glimmer-cui.hello"
  (:require [glimmer.ratom :as r]
            [glimmer.core :as g]
            [glimmer-cui.core]))

(defonce notifications (r/atom true))

(defn counter []
  (let [clicks (r/atom 0)]                ; Form-2: lives as long as the component
    (fn []
      [:row {:gap 8}
       [:button {:label "Click me" :variant :primary :on-click #(swap! clicks inc)}]
       [:text {:text (str "Clicked " @clicks " times")}]])))

(defn app []
  [:column {:pad 32 :gap 16}
   [:heading {:text "Hello, CUI" :level 2}]
   [:secondary {:text "A native window, a few components and no dependencies."}]
   [:switch {:label "Notifications" :checked @notifications :on-change #(reset! notifications %)}]
   [counter]])

(defn -main [& _]
  (g/run app :title "Hello" :width 420 :height 300))
