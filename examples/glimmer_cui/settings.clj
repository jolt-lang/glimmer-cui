(ns glimmer-cui.settings
  "A settings card: inputs, switches, a slider, a dialog, and the escape
  hatches (with-ui for a toast, :cui/immediate for a widget the tag table
  lacks). Run: jolt -M:examples -m glimmer-cui.settings"
  (:require [glimmer.ratom :as r]
            [glimmer.core :as g]
            [cui.core :as ui]
            [glimmer-cui.core :as gc]))

(defonce state (r/atom {:email "ada@example.com" :weekly true :dark false
                        :volume 0.6 :confirm false}))

(defn toast! [msg tone]
  (gc/with-ui #(ui/toast % msg {:tone tone})))

(defn confirm-dialog []
  (let [open (r/cursor state [:confirm])]
    (fn []
      [:dialog {:title "Reset all data?" :open @open
                :description "This removes every report. It cannot be undone."
                :on-close #(reset! open false)}
       [:dialog-footer
        [:button {:label "Cancel" :on-click #(reset! open false)}]
        [:button {:label "Reset" :variant :danger
                  :on-click #(do (reset! open false) (toast! "Data reset" :danger))}]]])))

(defn settings []
  (let [{:keys [email weekly dark volume]} @state]
    [:card {:width :fill :gap 12}
     [:row {:width :fill}
      [:heading {:text "Settings" :level 4}]
      [:spacer]
      [:badge {:text "Beta" :tone :violet}]]
     [:input {:value email :label "Notification email" :icon :mail :width :fill :clearable true
              :on-change #(swap! state assoc :email %)}]
     [:row {:gap 16 :wrap true}
      [:switch {:label "Weekly report" :checked weekly :on-change #(swap! state assoc :weekly %)}]
      [:switch {:label "Dark theme" :checked dark
                :on-change (fn [on]
                             (swap! state assoc :dark on)
                             (gc/with-ui #(ui/set-theme % (if on :dark :light))))}]]
     [:slider {:label "Volume" :value volume :min 0 :max 1 :show-value true :format "%.2f"
               :on-change #(swap! state assoc :volume %)}]
     [:progress {:value volume :label "Quota used"}]
     ;; a widget not in the tag table, drawn immediate-mode every frame
     [:cui/immediate {:render (fn [u] (ui/kbd u "Ctrl+S"))}]
     [:row {:gap 8}
      [:button {:label "Save" :variant :primary :icon :check
                :on-click #(toast! "Settings saved" :success)}]
      [:button {:label "Reset data" :variant :danger :icon :trash-2
                :on-click #(swap! state assoc :confirm true)}]]]))

(defn app []
  [:column {:pad 24 :gap 16 :width :fill}
   [:heading {:text "Larkspur" :level 2}]
   [:secondary {:text "Everything at a glance"}]
   [settings]
   [confirm-dialog]])

(defn -main [& _]
  (g/run app :title "Settings" :width 560 :height 560 :accent :blue))
