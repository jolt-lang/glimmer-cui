(ns glimmer-cui.node
  "Retained nodes: the widgets glimmer's reconciler creates, patches and
  arranges, kept on the Jolt side because CUI itself retains nothing.

  A node owns two kinds of native memory:

  - an ENCODING arena: its option struct and strings, encoded from props when
    the node is created and again only when its props change. A frame replays
    the node with these pointers and encodes nothing.
  - a STATE cell: the persistent C memory behind an out-parameter (a switch's
    bool, a slider's float, an input's text buffer). CUI edits it in place
    across frames; a patch overwrites it only when the prop itself changed.

  Memory a node lets go of (an arena replaced by a patch, a removed node's
  cells) is retired, not freed: CUI may still read option strings while it
  paints the frame they were passed in, so retired memory is released two
  frames later by reap!."
  (:require [jolt.ffi :as ffi]
            [cui.impl :as impl]))

;; ---------------------------------------------------------------------------
;; deferred release

(defonce ^{:doc "Frames rendered so far; reap! counts retirements against it."}
  frame-no (atom 0))

(defonce ^:private graveyard (atom []))

(defn retire!
  "Release memory with `free!` (a thunk) once the frames that may still read it
  have been painted."
  [free!]
  (swap! graveyard conj [@frame-no free!])
  nil)

(defn reap!
  "Release memory retired at least two frames ago. Called once per frame."
  []
  (let [now @frame-no
        [old _] (swap-vals! graveyard (fn [g] (filterv #(> (first %) (- now 2)) g)))]
    (doseq [[n free!] old :when (<= n (- now 2))]
      (free!))))

(defn retired-count
  "How many releases are waiting on reap!."
  []
  (count @graveyard))

(defn reap-all!
  "Release everything retired, regardless of age. For shutdown, after the
  context that could read it is gone."
  []
  (let [[old _] (reset-vals! graveyard [])]
    (doseq [[_ free!] old] (free!))))

;; ---------------------------------------------------------------------------
;; pending events
;;
;; A frame collects events while it replays the tree and fires them only after
;; the walk, so a handler's swap! can never reshape the tree mid-walk.

(defonce ^:private pending (volatile! []))

(defn fire!
  "Queue event `k` of `node` with `args`, fired after the current walk."
  [node k & args]
  (vswap! pending conj [node k args])
  nil)

(defn flush-events!
  "Fire the queued events with the handlers the nodes' latest props hold.
  A handler that throws is reported and the rest still run."
  []
  (let [evs @pending]
    (vreset! pending [])
    (doseq [[node k args] evs]
      (when-let [h (get @(:props node) k)]
        (try (apply h args)
             (catch Throwable e
               (binding [*out* *err*]
                 (println "glimmer-cui: handler" k "on" (:tag node) "threw:" (ex-message e)))))))
    (count evs)))

;; ---------------------------------------------------------------------------
;; nodes

(defonce ^:private serial (atom 0))

(defn make
  "A fresh node for `tag` described by widget `spec`."
  [tag spec]
  {:id (swap! serial inc)
   :tag tag
   :spec spec
   :props (volatile! {})
   :emit (volatile! nil)          ; compiled replay: leaf fn, or {:begin :end}
   :arena (volatile! nil)
   :cell (volatile! nil)          ; {:ptr :type :cap}
   :cell-val (volatile! nil)      ; last cell value seen, for edge events
   :children (volatile! [])
   :rect (volatile! nil)})

(defn with-encoding
  "Run (f) with a fresh arena bound as cui.impl's allocation arena, make it the
  node's encoding arena, and retire the previous one. Returns (f)."
  [node f]
  (let [a (ffi/confined-arena)
        r (try (binding [impl/*arena* a] (f))
               (catch Throwable e (ffi/close-arena a) (throw e)))
        old @(:arena node)]
    (vreset! (:arena node) a)
    (when old (retire! #(ffi/close-arena old)))
    r))

;; ---- state cells

(def ^:private cell-size {:bool 1 :float 4 :int 4})

(defn- write-cell! [{:keys [ptr type cap]} v]
  (case type
    :bool (ffi/write ptr :bool (boolean v))
    :float (ffi/write ptr :float (double (or v 0)))
    :int (ffi/write ptr :int (long (or v 0)))
    :text (let [s (str (or v ""))
                n (impl/utf8-len s)]
            (when (>= n cap)
              (throw (ex-info "glimmer-cui: text longer than its buffer; raise :max-length"
                              {:length n :cap cap})))
            (ffi/write-bytes ptr s)
            (ffi/write ptr :uint8 0 n))))

(defn read-cell
  "The value CUI left in the node's state cell."
  [node]
  (let [{:keys [ptr type]} @(:cell node)]
    (case type
      :bool (ffi/read ptr :bool)
      :float (ffi/read ptr :float)
      :int (ffi/read ptr :int)
      :text (ffi/ptr->string ptr))))

(defn cell-ptr [node] (:ptr @(:cell node)))
(defn cell-cap [node] (:cap @(:cell node)))

(defn set-cell!
  "Write `v` into the node's state cell, allocating it (or growing a text
  buffer to `cap` bytes) first when needed."
  [node type v cap]
  (let [c @(:cell node)
        c (if (and c (or (not= type :text) (>= (:cap c) cap)))
            c
            (let [n (if (= type :text) cap (cell-size type))
                  p (ffi/alloc n)
                  fresh {:ptr p :type type :cap n}]
              (when c
                ;; keep what the user typed when only the buffer grows
                (when (= type :text) (write-cell! fresh (read-cell node)))
                (let [old (:ptr c)] (retire! #(ffi/free old))))
              (vreset! (:cell node) fresh)
              fresh))]
    (write-cell! c v)
    (vreset! (:cell-val node) v)
    nil))

(defn dispose!
  "Retire the node's memory and its children's. The node must not be walked
  again."
  [node]
  (doseq [c @(:children node)] (dispose! c))
  (vreset! (:children node) [])
  (vreset! (:emit node) nil)
  (when-let [a @(:arena node)] (vreset! (:arena node) nil) (retire! #(ffi/close-arena a)))
  (when-let [c @(:cell node)] (vreset! (:cell node) nil) (let [p (:ptr c)] (retire! #(ffi/free p))))
  nil)

;; ---- children

(defn- index-of [v x] (first (keep-indexed (fn [i y] (when (identical? x y) i)) v)))

(defn append-child! [parent child] (vswap! (:children parent) conj child) nil)

(defn remove-child! [parent child]
  (vswap! (:children parent) (fn [cs] (filterv #(not (identical? % child)) cs)))
  nil)

(defn replace-child! [parent old new]
  (vswap! (:children parent)
          (fn [cs] (if-let [i (index-of cs old)] (assoc cs i new) (conj cs new))))
  nil)

(defn reorder-child!
  "Move `child` to sit right after `sibling` (nil: first)."
  [parent child sibling]
  (vswap! (:children parent)
          (fn [cs]
            (let [without (filterv #(not (identical? % child)) cs)
                  pos (if sibling (if-let [i (index-of without sibling)] (inc i) (count without)) 0)]
              (into (conj (subvec without 0 pos) child) (subvec without pos)))))
  nil)

(defn rect
  "The node's rectangle from the latest frame, {:x :y :w :h}. Recorded only for
  nodes given a :ref prop."
  [node]
  @(:rect node))
