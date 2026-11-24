(ns glimmer-cui.headless
  "Drive glimmer-cui without a window or a display: a bare CUI context that
  the caller steps frame by frame, feeds synthetic input, and renders to
  pixels with CUI's software rasterizer.

      (def s (open {:width 420 :height 300}))
      (mount! s [my-app])
      (step! s 3)
      (click! s (glimmer-cui.core/rect @save-button))
      (screenshot! s \"target/app.png\")
      (close! s)

  Frames run exactly as under the window loop (glimmer-cui.core/render-frame!),
  including glimmer's scheduled re-renders: while a session is open the
  backend counts as running, so a ratom change queues its re-render for the
  next step instead of rendering inline."
  (:require [jolt.ffi :as ffi]
            [cui.core :as ui]
            [cui.impl :as impl]
            [cui.raw :as raw]
            [glimmer.backend :as b]
            [glimmer.core :as g]
            [glimmer-cui.core :as gc]
            [glimmer-cui.node :as n]))

(def ^:private iface-layout
  (ffi/layout [:struct [[:user :pointer] [:texture_create :pointer]
                        [:texture_update :pointer] [:texture_destroy :pointer]]]))

(ffi/defcfn ^:private sw-create "cui_sw_create" [] :pointer)
(ffi/defcfn ^:private sw-destroy "cui_sw_destroy" [:pointer] :void)
(ffi/defcfn ^:private sw-iface "cui_sw_iface" [:pointer]
  [:by-value [:struct [[:user :pointer] [:texture_create :pointer]
                       [:texture_update :pointer] [:texture_destroy :pointer]]]])
(ffi/defcfn ^:private sw-set-threads "cui_sw_set_threads" [:pointer :int] :void)
(ffi/defcfn ^:private sw-render "cui_sw_render" [:pointer :pointer :pointer :int :int :int :int] :void)
(ffi/defcfn ^:private write-png "cui_write_png" [:string :pointer :int :int :int] :bool)

(defn open
  "A session: a bare CUI context of `width` x `height` points with a software
  renderer attached. Options: :width :height :scale :theme :accent :dt."
  [{:keys [width height scale theme accent dt]
    :or {width 420 height 300 scale 1 theme :light dt (/ 1.0 60)}}]
  (let [sw (sw-create)
        _ (sw-set-threads sw 1)
        iface (ffi/alloc (ffi/layout-size iface-layout))
        _ (sw-iface iface sw)
        u (ui/create (cond-> {:theme theme} accent (assoc :accent accent)))]
    (raw/cui_set_renderer u iface)
    (ui/set-viewport u width height scale)
    (reset! b/loop-running? true)
    {:ui u :sw sw :iface iface :width width :height height :scale scale :dt dt
     :root (gc/root-node) :mounted (atom nil)}))

(defn mount!
  "Mount hiccup (usually [component args...]) into the session's root."
  [{:keys [root mounted]} hiccup]
  (reset! mounted (g/mount root :cui/root hiccup)))

(defn frame!
  "Run one frame: begin, glimmer-cui's frame work, end."
  [{:keys [ui dt root]}]
  (raw/cui_begin_frame ui (float dt))
  (let [evs (gc/render-frame! ui root)]
    (raw/cui_end_frame ui)
    evs))

(defn step!
  "Run `k` frames (default 1)."
  ([s] (step! s 1))
  ([s k] (dotimes [_ k] (frame! s)) s))

(defn close!
  "Unmount, destroy the context and release every node's memory."
  [{:keys [ui sw iface root mounted]}]
  (when-let [m @mounted] (g/unmount! m root :cui/root))
  (reset! b/loop-running? false)
  (ui/destroy ui)
  (sw-destroy sw)
  (ffi/free iface)
  (n/reap-all!)
  nil)

;; ---- input, applied between frames like a platform layer would

(defn center [{:keys [x y w h]}] [(+ x (/ w 2.0)) (+ y (/ h 2.0))])

(defn move! [{:keys [ui]} [x y]] (raw/cui_input_mouse_move ui (float x) (float y)))

(defn click!
  "Move to the middle of `rect` and click, a frame per input event, the way a
  real pointer arrives."
  [s rect]
  (move! s (center rect)) (step! s)
  (raw/cui_input_mouse_button (:ui s) 0 true) (step! s)
  (raw/cui_input_mouse_button (:ui s) 0 false) (step! s))

(defn type-text! [{:keys [ui] :as s} text]
  (impl/call-with-arena #(raw/cui_input_text ui (impl/str-ptr text)))
  (step! s))

(defn key! [{:keys [ui] :as s} key]
  (raw/cui_input_key ui key true 0) (step! s)
  (raw/cui_input_key ui key false 0) (step! s))

;; ---- pixels

(defn screenshot!
  "Rasterize the latest frame into a PNG at `path`."
  [{:keys [ui sw width height scale]} path]
  (let [w (long (* width scale)) h (long (* height scale))
        px (ffi/alloc (* 4 w h))]
    (try
      (sw-render sw (raw/cui_get_draw_data ui) px w h (* 4 w) 0)
      (when-not (write-png path px w h (* 4 w))
        (throw (ex-info "glimmer-cui: could not write png" {:path path})))
      path
      (finally (ffi/free px)))))
