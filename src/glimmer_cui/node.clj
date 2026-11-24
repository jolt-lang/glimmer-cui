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

(defn defer!
  "Queue (f) to run after the current walk, in order with the events."
  [f]
  (vswap! pending conj [nil f nil])
  nil)

(defn flush-events!
  "Fire the queued events with the handlers the nodes' latest props hold.
  A handler that throws is reported and the rest still run."
  []
  (let [evs @pending]
    (vreset! pending [])
    (doseq [[node k args] evs]
      (when-let [h (if node (get @(:props node) k) k)]
        (try (apply h args)
             (catch Throwable e
               (binding [*out* *err*]
                 (println "glimmer-cui: handler" (if node k "write-back") "on" (:tag node) "threw:"
                          (ex-message e)))))))
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
   :cells (volatile! {})          ; prop -> {:ptr :kind :n :many :cap :arena}
   :cell-vals (volatile! {})      ; prop -> last value seen, for edge events
   :live (volatile! [])           ; atoms inside option structs, synced each frame
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
;;
;; A node has one cell per out-parameter, keyed by the prop that seeds it:
;; {prop {:ptr :kind :n :many :cap :arena}}. :kind is a cui.impl element kind
;; ([:bool], [:scalar :float], [:struct :cui_date] ...) or [:text] for a text
;; buffer. A collection prop (a toggle group's bools, a checklist's steps)
;; makes an array cell of :n elements. Each cell owns an arena, so strings
;; inside struct cells live exactly as long as the cell.

(defn- many?
  "Does `v` fill an array cell of `kind`? A vector of numbers is one color or
  date, not an array of them."
  [[k] v]
  (and (sequential? v) (or (not= k :struct) (not (number? (first v))))))

(defn- utf8-bytes
  "s as at most `cap` UTF-8 bytes, cut on a character boundary."
  [s cap]
  (let [bs (vec (.getBytes (str s) "UTF-8"))]
    (if (<= (count bs) cap)
      bs
      ;; back off continuation bytes (10xxxxxx) so no character is split
      (loop [n cap] (if (and (pos? n) (= 0x80 (bit-and (nth bs n) 0xC0))) (recur (dec n)) (subvec bs 0 n))))))

(defn prep-chars
  "Value `v` for element kind `kind`, with every string bound for an inline
  char[n] field (cui_kv_pair's key, say) turned into its UTF-8 bytes, leaving
  room for the NUL. cui.impl would otherwise write it a character at a time."
  [[k a n] v]
  (case k
    :array (cond (and (= a [:scalar :int8]) (string? v)) (utf8-bytes v (dec n))
                 (sequential? v) (mapv #(prep-chars a %) v)
                 :else v)
    :struct (if (map? v)
              (let [by-key (get-in impl/structs [a :by-key])]
                (reduce-kv (fn [m fk fv]
                             (assoc m fk (if-let [f (get by-key fk)] (prep-chars (:kind f) fv) fv)))
                           {} v))
              v)
    (:vec :ref) (if (sequential? v) (mapv #(prep-chars a %) v) v)
    v))

(defn- cell-size [kind n]
  (if (= [:text] kind) n (* n (impl/elem-size kind))))

(defn- encode-cell! [{:keys [ptr kind many cap]} v]
  (if (= [:text] kind)
    (let [s (str (or v ""))
          n (impl/utf8-len s)]
      (when (>= n cap)
        (throw (ex-info "glimmer-cui: text longer than its buffer; raise :max-length"
                        {:length n :cap cap})))
      (ffi/write-bytes ptr s)
      (ffi/write ptr :uint8 0 n))
    (let [items (if many (vec v) [v])
          s (impl/elem-size kind)
          wb (volatile! [])]
      (dotimes [i (count items)]
        (impl/encode! (+ ptr (* i s)) kind (prep-chars kind (nth items i)) wb)))))

(defn- char-array-str
  "An inline char[n] field as a string, up to its NUL or its end."
  [p n]
  (let [bs (loop [i 0 acc []]
             (if (= i n) acc
                 (let [b (ffi/read p :int8 i)] (if (zero? b) acc (recur (inc i) (conj acc b))))))]
    (String. (byte-array bs) "UTF-8")))

(defn decode
  "cui.impl/decode, except that inline char arrays (cui_kv_pair's key, say)
  read as strings rather than byte vectors."
  [p [k a n :as kind]]
  (case k
    :array (if (= a [:scalar :int8])
             (char-array-str p n)
             (let [s (impl/elem-size a)] (mapv #(decode (+ p (* % s)) a) (range n))))
    :struct (into {} (for [f (:fields (get impl/structs a)) :when (not= :_ (:ckey f))]
                       [(:key f) (decode (+ p (:off f)) (:kind f))]))
    (impl/decode p kind)))

(defn- decode-cell [{:keys [ptr kind n many]}]
  (cond (= [:text] kind) (ffi/ptr->string ptr)
        many (let [s (impl/elem-size kind)] (mapv #(decode (+ ptr (* % s)) kind) (range n)))
        :else (decode ptr kind)))

(defn read-cell
  "The value CUI left in the node's cell `k`, decoded: a bool, number, string,
  map for a struct, or vector for an array cell."
  [node k]
  (decode-cell (get @(:cells node) k)))

(defn cell [node k] (get @(:cells node) k))
(defn cell-ptr [node k] (:ptr (cell node k)))
(defn cell-cap [node k] (:cap (cell node k)))
(defn cell-n [node k] (:n (cell node k)))

(defn set-cell!
  "Write `v` into the node's cell `k` of element `kind`, in fresh memory, and
  retire the old cell. A text cell holds `cap` bytes; an array cell holds at
  least `cap` elements (room for an editor to add to). Text the user typed is
  kept when only a text buffer grows (`keep?`)."
  ([node k kind v cap] (set-cell! node k kind v cap false))
  ([node k kind v cap keep?]
   (let [old (cell node k)
         text? (= [:text] kind)
         v (if keep? (decode-cell old) v)
         many (and (not text?) (many? kind v))
         n (if many (count v) 1)
         slots (if text? cap (max n (long (or cap 0)) 1))
         a (ffi/confined-arena)
         c {:ptr (ffi/alloc a (max 1 (cell-size kind slots))) :kind kind :n n :many many
            :cap slots :arena a}]
     (try (binding [impl/*arena* a] (encode-cell! c v))
          (catch Throwable e (ffi/close-arena a) (throw e)))
     (vswap! (:cells node) assoc k c)
     (vswap! (:cell-vals node) assoc k (decode-cell c))
     (when old (let [oa (:arena old)] (retire! #(ffi/close-arena oa))))
     nil)))

(defn set-cell-n!
  "Record that CUI changed the element count of array cell `k` (an editor
  added or removed rows)."
  [node k n]
  (vswap! (:cells node) assoc-in [k :n] n)
  nil)

(defn cell-change
  "The cell's current value when it differs from the last one seen (which it
  then becomes), else ::same."
  [node k]
  (let [v (read-cell node k)]
    (if (= v (get @(:cell-vals node) k))
      ::same
      (do (vswap! (:cell-vals node) assoc k v) v))))

(defn last-cell-val [node k] (get @(:cell-vals node) k))

(defn note-cell-val! [node k v] (vswap! (:cell-vals node) assoc k v) nil)

(defn dispose!
  "Retire the node's memory and its children's. The node must not be walked
  again."
  [node]
  (doseq [c @(:children node)] (dispose! c))
  (vreset! (:children node) [])
  (vreset! (:emit node) nil)
  (when-let [a @(:arena node)] (vreset! (:arena node) nil) (retire! #(ffi/close-arena a)))
  (doseq [[_ c] @(:cells node)] (let [a (:arena c)] (retire! #(ffi/close-arena a))))
  (vreset! (:cells node) {})
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
