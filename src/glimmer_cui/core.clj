(ns glimmer-cui.core
  "The CUI backend for glimmer: reagent-style components over CUI's
  immediate-mode widgets.

      (require '[glimmer.ratom :as r] '[glimmer.core :as g] 'glimmer-cui.core)

      (defn counter []
        (let [n (r/atom 0)]
          (fn []
            [:column {:pad 24 :gap 12}
             [:heading {:text (str \"Clicked \" @n \" times\")}]
             [:button {:label \"Click me\" :variant :primary :on-click #(swap! n inc)}]])))

      (g/run counter :title \"Counter\" :width 360 :height 200)

  CUI keeps no widget tree: every frame it is shown, the whole UI must be
  submitted again. glimmer-cui keeps the tree on the Jolt side as nodes
  (glimmer-cui.node) and replays it each frame with option structs encoded
  when props last changed. Components re-render only when a ratom they read
  changes; a frame where nothing changed runs no component code and encodes
  nothing, it only walks the nodes.

  One frame, in order (render-frame!):
    1. run the work glimmer scheduled (re-renders, which patch nodes)
    2. run one-shot with-ui calls, then replay the tree
    3. fire the events the replay collected (handlers run after the walk)
    4. ask for another frame when the handlers scheduled more work"
  (:require [jolt.ffi :as ffi]
            [cui.core :as ui]
            [cui.impl :as impl]
            [cui.raw :as raw]
            [glimmer.backend :as b]
            [glimmer.core :as g]
            [glimmer-cui.node :as n]
            [glimmer-cui.widgets :as w]))

(ffi/defcfn ^:private cui-app-wake "cui_app_wake" [:pointer] :void)

;; ---------------------------------------------------------------------------
;; scheduled work and one-shot UI calls

(defonce ^:private work-queue (atom []))
(defonce ^:private ui-calls (atom []))
(defonce ^:private running-app (atom nil))
(defonce ^:private current-ui (atom nil))

(defn- wake! []
  (when-let [app @running-app] (cui-app-wake app)))

(defn- schedule
  "glimmer's :schedule. Any thread: queue the work for the next frame and wake
  the loop if it is waiting for input."
  [work]
  (swap! work-queue conj work)
  (wake!)
  nil)

(defn with-ui
  "Escape hatch for imperative CUI calls: run (f ui) once, inside the next
  frame, before the tree is replayed. For things CUI exposes as actions rather
  than state: a toast, scroll-to, set-theme, focus. Any thread."
  [f]
  (swap! ui-calls conj f)
  (wake!)
  nil)

(defn- drain! [q]
  (let [[jobs _] (reset-vals! q [])]
    (doseq [j jobs] (j))
    (count jobs)))

;; ---------------------------------------------------------------------------
;; replay

(def ^:private rect-scratch (delay (ffi/alloc 16)))

(defn- record-rect! [ui node]
  (let [p @rect-scratch]
    (raw/cui_last_rect p ui)
    (vreset! (:rect node) (impl/read-struct :cui_rect p))))

(defn- walk-children!
  "Replay a node's children, in order. Each child's replay is a closure
  compiled when its props last changed (see replay-fn)."
  [ui node]
  (let [cs @(:children node)]
    (dotimes [i (count cs)] (@(:emit (nth cs i)) ui))))

(defn- replay-fn
  "The closure a frame calls to replay `node`, built from the widget's
  compiled replay `e` once per props change, so a frame does no lookups, no
  prop checks and no exception frames per node.

  A node replays in its own id scope (the node's serial) when its spec's
  :scope? says so: by default every leaf, and every generated widget not
  given an explicit :id. CUI's per-id state (focus, animation, an input's
  caret) then follows the node for its whole life and two buttons with one
  label never collide. A container's children are read when it replays, so
  appends and moves need no recompile."
  [node e ref? scope?]
  (let [id (:id node)
        body (if (fn? e)
               e
               (let [{:keys [begin end]} e]
                 (if (:conditional (:spec node))
                   (fn [ui] (when (begin ui) (walk-children! ui node) (end ui)))
                   (fn [ui] (begin ui) (walk-children! ui node) (end ui)))))
        scoped (if scope?
                 (fn [ui] (raw/cui_push_id_int ui id) (body ui) (raw/cui_pop_id ui))
                 body)]
    (if ref? (fn [ui] (scoped ui) (record-rect! ui node)) scoped)))

(def ^:dynamic *stats*
  "When bound to an atom, counters accumulate into it: :frames, :work
  (scheduled jobs run), :events, and :encodes (nodes whose props were
  encoded, on create or patch)."
  nil)

(defn render-frame!
  "One frame's work between CUI's begin and end of frame: see the ns doc.
  Returns the number of events fired."
  [ui root]
  (let [jobs (drain! work-queue)
        arena (ffi/confined-arena)]
    (try
      (binding [impl/*arena* arena]
        (let [[calls _] (reset-vals! ui-calls [])]
          (doseq [f calls] (f ui)))
        (walk-children! ui root))
      (finally
        ;; cui.core calls from :cui/immediate and with-ui allocated here;
        ;; CUI may read them until this frame is painted
        (n/retire! #(ffi/close-arena arena))))
    (let [evs (n/flush-events!)]
      (when (seq @work-queue) (raw/cui_request_redraw ui))
      (n/reap!)
      (swap! n/frame-no inc)
      (when *stats* (swap! *stats* #(-> % (update :frames (fnil inc 0))
                                           (update :work (fnil + 0) jobs)
                                           (update :events (fnil + 0) evs))))
      evs)))

;; ---------------------------------------------------------------------------
;; the backend

(declare run-app)

(defn- compile! [node props]
  (when *stats* (swap! *stats* update :encodes (fnil inc 0)))
  (let [spec (:spec node)]
    (n/with-encoding node
      #(let [e ((:compile spec) node props)
             scope? (if-let [f (:scope? spec)] (f props) (fn? e))]
         (vreset! (:emit node) (replay-fn node e (contains? props :ref) scope?))))
    (vreset! (:props node) props)))

(defn- cell-cap
  "Room a cell needs for prop value v: bytes for text (:max-length, default
  1024), elements for a growable array (:capacity, default twice the count
  and at least 16)."
  [{:keys [kind growable]} v props]
  (cond (= [:text] kind) (max (long (or (:max-length props) 1024)) (inc (* 4 (count (str v)))))
        growable (max (long (or (:capacity props) (max 16 (* 2 (count v))))) (count v))
        :else nil))

(defn- sync-cells!
  "Seed or overwrite the node's state cells from their props, glimmer's rule
  for user-edited values: only when the prop itself changed (or on create),
  so a re-render that repeats the old value leaves the user's edit alone. A
  text buffer whose :max-length grows is reallocated keeping its text."
  [node old-props props]
  (doseq [{:keys [prop kind growable] :as c} (:cells (:spec node))]
    (let [v (get props prop)
          v (cond growable (vec v)
                  (and (nil? v) (= [:bool] kind)) false
                  :else v)
          cap (cell-cap c v props)
          fresh? (or (nil? (n/cell node prop)) (not= (get props prop) (get old-props prop)))]
      (cond fresh?
            (do (n/set-cell! node prop kind v cap)
                (when growable (n/set-cell! node (w/count-key prop) [:scalar :int] (count v) nil)))
            (and cap (> cap (n/cell-cap node prop)))
            (n/set-cell! node prop kind nil cap true)))))

(defn create-node!
  "glimmer's :create!."
  [tag props]
  (let [node (n/make tag (w/spec-for tag))]
    (sync-cells! node nil props)
    (compile! node props)
    (when-let [f (:ref props)] (f node))
    node))

(defn- patch-node! [_tag node old-props props]
  (when-not (= old-props props)
    (sync-cells! node old-props props)
    (compile! node props)))

(def backend
  {:name           :cui
   :create!        create-node!
   :apply-props!   (fn [tag node props] (patch-node! tag node @(:props node) props))
   :patch-props!   patch-node!
   :append-child!  (fn [_ parent child] (n/append-child! parent child))
   :remove-child!  (fn [_ parent child] (n/remove-child! parent child) (n/dispose! child))
   :replace-child! (fn [_ parent old new] (n/replace-child! parent old new) (n/dispose! old))
   :reorder-child! (fn [_ parent child sibling] (n/reorder-child! parent child sibling))
   :dispose!       n/dispose!
   :schedule       schedule
   :text->element  (fn [s] [:text {:text (str s)}])
   :run-lifecycle  (fn [opts lifecycle] (run-app opts lifecycle))})

(b/register! backend)

(defn root-node
  "A container that only holds the top-level nodes; its replay is its
  children's."
  []
  (n/make :cui/root {:kind :container}))

;; ---------------------------------------------------------------------------
;; the window loop

(defn- app-desc [opts]
  (merge {:title "glimmer" :width 400 :height 300}
         (select-keys opts [:title :width :height :min-width :min-height :fixed-size
                            :continuous :no-vsync :hidden :renderer :theme :accent
                            :follow-system-theme])))

(defn run-app
  "glimmer's :run-lifecycle: open a CUI window, mount the root, replay it every
  frame CUI wakes for, unmount and destroy the window when it closes."
  [opts {:keys [mount! unmount! abandon!]}]
  (let [app (impl/call-with-arena #(ui/app-create (app-desc opts)))]
    (when (or (nil? app) (zero? app))
      (throw (ex-info "glimmer-cui: cui_app_create failed (no display?)" {:opts opts})))
    (let [root (root-node)
          u (raw/cui_app_ui app)
          deadline (when-let [ms (:auto-quit-ms opts)] (+ (System/currentTimeMillis) ms))
          unmounted (atom false)]
      (reset! running-app app)
      (reset! current-ui u)
      (reset! b/loop-running? true)
      (try
        (mount! root :cui/root)
        (binding [impl/*app* app]
          (loop []
            (when (raw/cui_app_frame app)
              (render-frame! u root)
              (when deadline
                (if (> (System/currentTimeMillis) deadline)
                  (raw/cui_app_quit app)
                  (raw/cui_request_wakeup u (float 0.05))))
              (recur))))
        (unmount!)
        (reset! unmounted true)
        (finally
          (when-not @unmounted (abandon!))
          (reset! b/loop-running? false)
          (reset! running-app nil)
          (reset! current-ui nil)
          (reset! work-queue [])
          (raw/cui_app_destroy app)
          (n/reap-all!))))))

;; ---------------------------------------------------------------------------
;; handles

(defn rect
  "The rectangle a node took in the latest frame, {:x :y :w :h}. Only nodes
  given a :ref prop record one: [:button {:ref #(reset! save-btn %) ...}]."
  [node]
  (n/rect node))
