(ns glimmer-cui.bench
  "Per-frame cost of the same UI built two ways, on the same bare CUI context
  with the software renderer attached (so text is shaped as in a window):

    direct   cui-jolt's immediate mode: the frame fn runs every frame and
             encodes every widget's options from Clojure values.
    glimmer  glimmer-cui: components render when a ratom changes; a frame
             replays nodes with pre-encoded options.

  Scenarios per UI: idle (nothing changes, e.g. hover or animation frames)
  and one change per frame (a single row's state flips every frame).

  jolt bench   (or jolt -M:bench)"
  (:require [cui.core :as ui]
            [cui.raw :as raw]
            [glimmer.ratom :as r]
            [glimmer-cui.core :as gc]
            [glimmer-cui.headless :as h]))

(def warmup 60)
(def frames 300)

(defn- stats [ns-samples]
  (let [v (vec (sort ns-samples)) k (count v)
        us #(/ (double %) 1000.0)]
    {:mean (us (/ (reduce + v) k)) :p50 (us (nth v (quot k 2))) :p95 (us (nth v (long (* 0.95 k))))}))

(defn- time-frames [frame-fn]
  (dotimes [i warmup] (frame-fn i))
  (stats (for [i (range frames)]
           (let [t (System/nanoTime)] (frame-fn (+ warmup i)) (- (System/nanoTime) t)))))

;; ---------------------------------------------------------------------------
;; the UI: a header and N rows of text, switch, button, badge

(defn- direct-frame [rows-state changes]
  (fn [u]
    (ui/column u {:pad 16 :gap 6 :width :fill}
      (ui/heading u "Devices" 3)
      (ui/secondary u (str (count rows-state) " devices, " @changes " changes"))
      (dotimes [i (count rows-state)]
        (let [on (nth rows-state i)]
          (ui/row u {:gap 8 :width :fill}
            (ui/text u (str "Device " i))
            (ui/spacer u)
            (ui/badge u (if @on "online" "offline") {:tone (if @on :success :neutral)})
            (ui/switch u (str "Enabled###sw" i) on)
            (when (ui/button u (str "Details###b" i) {:size :sm})
              (swap! changes inc))))))))

(defn- device-row [i on changes]
  [:row {:gap 8 :width :fill}
   [:text {:text (str "Device " i)}]
   [:spacer]
   [:badge {:text (if @on "online" "offline") :tone (if @on :success :neutral)}]
   [:switch {:label "Enabled" :checked @on :on-change #(reset! on %)}]
   [:button {:label "Details" :size :sm :on-click #(swap! changes inc)}]])

(defn- glimmer-app [rows-state changes]
  (fn []
    [:column {:pad 16 :gap 6 :width :fill}
     [:heading {:text "Devices" :level 3}]
     [:secondary {:text (str (count rows-state) " devices, " @changes " changes")}]
     (for [i (range (count rows-state))]
       ^{:key i} [device-row i (nth rows-state i) changes])]))

(defn- session [] (h/open {:width 640 :height 900}))

(defn- bench-direct [n change?]
  (let [rows (vec (repeatedly n #(atom true)))
        changes (atom 0)
        s (session)
        f (direct-frame rows changes)
        u (:ui s)]
    (try
      (time-frames (fn [i]
                     (when change? (swap! (nth rows (mod i n)) not))
                     (raw/cui_begin_frame u (float (/ 1.0 60)))
                     (ui/with-frame (f u))
                     (raw/cui_end_frame u)))
      (finally (h/close! s)))))

(defn- bench-glimmer [n change?]
  (let [rows (vec (repeatedly n #(r/atom true)))
        changes (r/atom 0)
        s (session)]
    (try
      (h/mount! s [(glimmer-app rows changes)])
      (time-frames (fn [i]
                     (when change? (swap! (nth rows (mod i n)) not))
                     (h/frame! s)))
      (finally (h/close! s)))))

(defn- empty-frame []
  (let [s (session) u (:ui s)]
    (try (time-frames (fn [_] (raw/cui_begin_frame u (float (/ 1.0 60))) (raw/cui_end_frame u)))
         (finally (h/close! s)))))

(defn- fmt [{:keys [mean p50 p95]}] (format "%9.1f %9.1f %9.1f" mean p50 p95))

(defn -main [& args]
  (let [sizes (if (seq args) (map parse-long args) [10 50 200])]
    (println (format "frame cost in microseconds (%d frames after %d warmup)" frames warmup))
    (println (format "%-34s %9s %9s %9s" "" "mean" "p50" "p95"))
    (println (format "%-34s %s" "empty frame (begin+end only)" (fmt (empty-frame))))
    (doseq [n sizes]
      (println (str "\n" n " rows (" (+ 2 (* 5 n)) " widgets)"))
      (let [rows [["direct, idle" (bench-direct n false)]
                  ["glimmer-cui, idle" (bench-glimmer n false)]
                  ["direct, 1 row changes per frame" (bench-direct n true)]
                  ["glimmer-cui, 1 row changes/frame" (bench-glimmer n true)]]]
        (doseq [[label st] rows] (println (format "  %-32s %s" label (fmt st))))
        (let [[[_ di] [_ gi] [_ dc] [_ gc*]] rows]
          (println (format "  speedup: idle %.1fx, changing %.1fx"
                           (/ (:mean di) (:mean gi)) (/ (:mean dc) (:mean gc*)))))))))
