(ns glimmer-cui.generated-test
  "Headless tests for the generated tag table: array props, struct and array
  out-parameters, growable arrays, atoms in option structs, struct returns and
  generated containers. Click positions come from CUI's layout at these
  window sizes."
  (:require [clojure.test :refer [deftest is testing]]
            [cui.core :as ui]
            [cui.raw :as raw]
            [glimmer.ratom :as r]
            [glimmer-cui.core :as gc]
            [glimmer-cui.headless :as h]
            [glimmer-cui.node :as n]
            [glimmer-cui.tags :as tags]
            [glimmer-cui.widgets :as w]))

(defn- with-session* [opts hiccup f]
  (let [s (h/open opts)]
    (try (h/mount! s hiccup) (f s) (finally (h/close! s)))))

(defmacro with-session [[s hiccup & [opts]] & body]
  `(with-session* (merge {:width 420 :height 400} ~opts) ~hiccup (fn [~s] ~@body)))

(defmacro counting [& body]
  `(let [st# (atom {})] (binding [gc/*stats* st#] ~@body) @st#))

;; ---------------------------------------------------------------------------

(deftest every-generated-tag-renders-with-no-props
  (doseq [t (sort (concat (keys tags/leaves) (keys tags/containers)))]
    (testing t
      (with-session [s [(fn [] [:column [t {}]])] {:width 600 :height 500}]
        (h/step! s 2)
        (is true)))))

(deftest hand-written-specs-take-precedence
  (is (= (:cells (w/spec-for :switch)) [{:prop :checked :kind [:bool]}]))
  (is (contains? w/generated :date-picker))
  (is (> (count w/specs) 250)))

(deftest string-array-props-and-int-cells
  (let [ev (atom []) seg (atom nil)]
    (with-session [s [(fn [] [:column {:pad 20}
                              [:segmented {:items ["One" "Two" "Three"] :index 0 :full-width true
                                           :ref #(reset! seg %) :on-change #(swap! ev conj %)}]])]
                   {:height 300}]
      (h/step! s 2)
      (let [{:keys [x y w h]} (gc/rect @seg)]
        (h/click! s {:x (+ x (* w 0.7)) :y y :w (* w 0.2) :h h}))
      (is (= [2] @ev))
      (is (= 2 (n/read-cell @seg :index))))))

(deftest struct-out-parameters-are-maps
  (let [days (atom [])]
    (with-session [s [(fn [] [:column {:pad 20}
                              [:calendar {:value {:year 2026 :month 11 :day 23}
                                          :on-change #(swap! days conj %)}]])]]
      (h/step! s 2)
      (h/click! s {:x 98 :y 235 :w 20 :h 20})          ; the 25th
      (is (= [{:year 2026 :month 11 :day 25}] @days))))
  (testing "colors are seeded from any cui-jolt color form"
    (let [pc (atom nil)]
      (with-session [s [(fn [] [:prop-color {:label "Fill" :value "#6366f1" :ref #(reset! pc %)}])]]
        (h/step! s)
        (is (= {:r 99 :g 102 :b 241 :a 255} (n/read-cell @pc :value)))))))

(deftest array-cells-and-several-cells-per-widget
  (let [ev (atom [])]
    (with-session [s [(fn [] [:column {:pad 20 :gap 16}
                              [:toggle-group {:labels ["B" "I" "U"] :values [false true false]
                                              :on-change #(swap! ev conj [:tg %])}]
                              [:range-slider {:label "Price" :lo 10 :hi 80 :min 0 :max 100
                                              :on-lo-change #(swap! ev conj [:lo %])
                                              :on-hi-change #(swap! ev conj [:hi %])}]])]
                   {:height 300}]
      (h/step! s 2)
      (h/click! s {:x 28 :y 28 :w 20 :h 20})          ; B
      (h/click! s {:x 370 :y 100 :w 4 :h 10})         ; the track near the top end
      (is (= [:tg [true true false]] (first @ev)))
      (is (= :hi (first (second @ev))))
      (is (> (second (second @ev)) 90)))))

(deftest growable-arrays-report-added-rows
  (let [ev (atom []) kv (atom nil)]
    (with-session [s [(fn [] [:column {:pad 20}
                              [:kv-editor {:pairs [{:key "host" :value "localhost"} {:key "port" :value "8080"}]
                                           :ref #(reset! kv %) :on-change #(swap! ev conj %)}]])]
                   {:width 460 :height 300}]
      (h/step! s 2)
      (is (= 16 (n/cell-cap @kv :pairs)) "room to grow, by default")
      (h/click! s {:x 60 :y 176 :w 100 :h 20})        ; the blank row's key field
      (h/type-text! s "user")
      (is (= ["host" "port" "user"] (map :key (last @ev))) "inline char arrays read as strings")
      (is (= 3 (n/cell-n @kv :pairs)))))
  (testing "inline char arrays take UTF-8, cut on a character boundary"
    (let [kv (atom nil)]
      (with-session [s [(fn [] [:kv-editor {:pairs [{:key "café" :value "日本"}
                                                     {:key (apply str (repeat 40 "é")) :value ""}]
                                             :ref #(reset! kv %)}])]]
        (h/step! s)
        (let [[a b] (n/read-cell @kv :pairs)]
          (is (= ["café" "日本"] [(:key a) (:value a)]))
          (is (= (apply str (repeat 31 "é")) (:key b)) "62 of key's 63 bytes"))))))

(deftest atoms-in-option-structs-sync-both-ways
  (let [vote (atom 0)]
    (with-session [s [(fn [] [:column {:pad 20} [:response-actions {:vote vote}]])] {:height 200}]
      (h/step! s 2)
      (h/click! s {:x 52 :y 20 :w 30 :h 30})          ; thumbs up
      (h/step! s)
      (is (= 1 @vote))
      (reset! vote -1)
      (h/step! s 2)
      (is (= -1 @vote) "the atom's own change is pushed to C, not overwritten"))))

(deftest array-props-re-encode-when-they-change
  (let [data (r/atom [1 3 2 5])]
    (with-session [s [(fn [] [:line-chart {:series [{:name "Load" :values @data}] :height 200}])]]
      (h/step! s 2)
      (let [st (counting (swap! data conj 9) (h/step! s))]
        (is (= 1 (:encodes st))))
      (is (nil? (:encodes (counting (h/step! s 5)))) "idle frames encode nothing"))))

(deftest struct-returns-fire-on-result-when-they-change
  (let [ev (atom [])]
    (with-session [s [(fn [] [:column {:pad 20}
                              [:path-bar {:segments ["home" "dev" "glimmer"] :on-result #(swap! ev conj %)}]])]
                   {:width 460 :height 200}]
      (h/step! s 3)
      (h/click! s {:x 20 :y 20 :w 30 :h 30})          ; "home"
      (is (= 0 (:clicked (first @ev)))))))

(deftest generated-containers
  (testing "a conditional container with an open cell reports :on-close"
    (let [ev (atom []) open (r/atom true) body (atom nil)]
      (with-session [s [(fn [] [:column
                                [:sheet {:title "Settings" :open @open
                                         :on-close #(do (swap! ev conj :closed) (reset! open false))}
                                 [:text {:text "body" :ref #(reset! body %)}]]])]]
        (h/step! s 10)
        (is (some? (gc/rect @body)))
        (h/key! s raw/CUI_KEY_ESCAPE)
        (h/step! s 2)
        (is (= [:closed] @ev))
        (is (false? @open)))))
  (testing "an explicit :id leaves the widget in its parent's id scope, so CUI calls can name it"
    (let [body (atom nil)]
      (with-session [s [(fn [] [:column [:popover {:id "pop"} [:text {:text "in" :ref #(reset! body %)}]]])]]
        (h/step! s 2)
        (is (nil? (gc/rect @body)))
        (gc/with-ui #(ui/popover-open % "pop"))
        (h/step! s 5)
        (is (some? (gc/rect @body)))))))
