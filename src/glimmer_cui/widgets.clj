(ns glimmer-cui.widgets
  "The tag table: what each hiccup tag replays as.

  A widget spec is a map:

    :kind        :leaf or :container
    :conditional true when a container's children (and its end call) run
                 only while begin returns true, like CUI's dialog
    :cells       [{:prop k :kind ek}] for a widget's out-parameters: prop k
                 seeds a persistent C cell of cui.impl element kind ek
                 ([:bool], [:scalar :float], [:struct :cui_date] ...) or
                 [:text] for a text buffer. A collection prop makes an array
                 cell. :growable marks an array CUI may add elements to.
    :scope?      (fn [props]) true when the node replays in its own CUI id
                 scope. Default: leaves yes, containers no.
    :compile     (fn [node props] -> replay). Runs once per props change, with
                 the node's encoding arena bound, so cui.impl's encoders
                 allocate there. For a leaf the replay is (fn [ui]); for a
                 container it is {:begin (fn [ui]) :end (fn [ui])}. Replays
                 close over pre-encoded pointers: a frame encodes nothing.

  Props not consumed as positional arguments become the widget's CUI options
  struct, with cui-jolt's conventions (keywords for enums and icons, :fill,
  colors, …). :key, :ref, :on-* handlers and :cui/* keys are never encoded.

  `specs` is the hand-written table over the generated one (glimmer-cui.tags,
  every CUI component), compiled here by compile-entry."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [cui.impl :as impl]
            [cui.raw :as raw]
            [glimmer-cui.node :as n]
            [glimmer-cui.tags :as tags]))

(defn- reserved? [k]
  (or (#{:key :ref} k)
      (and (keyword? k) (or (str/starts-with? (name k) "on-") (= "cui" (namespace k))))))

(declare opts-ptr*)

(defn opts-ptr
  "An options struct of type `t` encoded from `props`, leaving out `consumed`
  keys and reserved ones. Atoms in it register in `wb` (see live-refs)."
  ([t props consumed] (opts-ptr (volatile! []) t props consumed))
  ([wb t props consumed]
   (opts-ptr* wb t props consumed)))

(defn- opts-ptr* [wb t props consumed]
  (impl/in-ptr! wb [:struct t]
                (n/prep-chars [:struct t]
                              (reduce-kv (fn [m k v] (if (or (reserved? k) (contains? consumed k)) m (assoc m k v)))
                                         {} props))))

(defn- s [x] (impl/str-ptr x))

(defn- leaf
  "A leaf whose CUI call takes a string first arg (prop `k`) and an options
  struct `t`, with no events."
  [k t f]
  {:kind :leaf
   :compile (fn [_ p] (let [a (s (get p k)) o (opts-ptr t p #{k})] (fn [ui] (f ui a o))))})

(defn- box-container [begin-fn t]
  {:kind :container
   :compile (fn [_ p] (let [o (opts-ptr t p #{})] {:begin (fn [ui] (begin-fn ui o)) :end raw/cui_box_end}))})

(def ^:private changed-bit raw/CUI_CHANGED)
(def ^:private submitted-bit raw/CUI_SUBMITTED)

(defn- check-widget [cfn]
  {:kind :leaf
   :cells [{:prop :checked :kind [:bool]}]
   :compile (fn [node p]
              (let [l (s (:label p)) o (opts-ptr :cui_check_opts p #{:label :checked}) c (n/cell-ptr node :checked)]
                (fn [ui]
                  (when (cfn ui l c o)
                    (let [v (n/read-cell node :checked)]
                      (n/note-cell-val! node :checked v)
                      (n/fire! node :on-change v))))))})

;; ---------------------------------------------------------------------------
;; generated components: compile-entry turns a glimmer-cui.tags entry into a
;; widget spec. Every argument is encoded once per props change, as for the
;; hand-written specs; a frame only calls C and looks at what changed.

(defmacro ^:private invoker
  "A closure (fn [ui]) that calls f with ui and the pre-built args `as`, after
  a struct-return destination when `dst` is non-nil, without apply."
  [f dst as]
  (let [ui (gensym "ui") n 14
        syms (mapv #(symbol (str "a" %)) (range n))
        arity (fn [k with-dst]
                (let [xs (subvec syms 0 k)]
                  `(let [~@(mapcat (fn [x i] [x `(nth ~as ~i)]) xs (range k))]
                     (fn [~ui] ~(if with-dst `(~f ~dst ~ui ~@xs) `(~f ~ui ~@xs))))))]
    `(if ~dst
       (case (count ~as)
         ~@(mapcat (fn [k] [k (arity k true)]) (range n))
         (fn [~ui] (apply ~f ~dst ~ui ~as)))
       (case (count ~as)
         ~@(mapcat (fn [k] [k (arity k false)]) (range n))
         (fn [~ui] (apply ~f ~ui ~as))))))

(defn- call-fn [f dst as] (invoker f dst as))

(defn- arg-props
  "The props an entry's positional args consume, so the options struct
  leaves them out."
  [args cells]
  (cond-> (into #{} (keep (fn [[k prop]] (when-not (= k :opts) prop))) args)
    (some #(= [:text] (:kind %)) cells) (conj :max-length)
    (some :growable cells) (conj :capacity)))

(defn count-key [prop] [::count prop])

(defn- build-args [node p wb args consumed]
  (reduce
   (fn [acc [k prop ek flag]]
     (let [v (get p prop)]
       (case k
         :str (conj acc (s (if (nil? v) "" v)))
         :str-len (let [x (impl/str-arg (if (nil? v) "" v))] (conj acc (s x) (impl/utf8-len x)))
         :strs (conj acc (if (nil? v) 0 (impl/strs-ptr v)) (count v))
         :vec (conj acc (impl/vec-ptr! wb ek (n/prep-chars [:vec ek] v)) (count v))
         :in (conj acc (impl/in-ptr! wb ek (if (= :struct (first ek)) (n/prep-chars ek v) v)))
         :val (conj acc (cond (not= :struct (first ek)) (impl/arg-val ek v)
                              ;; a by-value struct left out is all zeros: CUI's unset
                              (nil? v) (impl/alloc (second ek))
                              :else (impl/in-ptr! wb ek v)))
         :mods (conj acc (impl/mods v))
         :raw (conj acc (or v 0))
         :buf (conj acc (n/cell-ptr node prop) (n/cell-cap node prop))
         :cell (cond-> (conj acc (n/cell-ptr node prop)) (= flag :count) (conj (n/cell-n node prop)))
         :cell-cap (conj acc (n/cell-ptr node prop) (n/cell-ptr node (count-key prop)) (n/cell-cap node prop))
         ;; for :opts the second slot is the struct name
         :opts (conj acc (opts-ptr* wb prop p consumed)))))
   [] args))

;; ---- atoms inside option structs or arrays ({:selected sel} for an int*
;; field): CUI edits the C copy encoded at compile time. Each frame pushes the
;; atom's value in when the atom changed, and after the call writes CUI's
;; edits back to the atom once the walk is done.

(defn- live-refs [wb]
  (vec (for [[k p ek many box cur] @wb :when (= k :ref)]
         {:p p :ek ek :many many :box box :n (if many (count cur) 1) :last (volatile! cur)})))

(defn- ref-read [{:keys [p ek many n]}]
  (if many
    (let [s (impl/elem-size ek)] (mapv #(n/decode (+ p (* % s)) ek) (range n)))
    (n/decode p ek)))

(defn- push-live! [refs]
  (doseq [{:keys [p ek many n box last]} refs]
    (let [v @box]
      (when-not (= v @last)
        (vreset! last v)
        (let [items (if many (vec v) [v]) s (impl/elem-size ek) wb (volatile! [])]
          (dotimes [i (min n (count items))]
            (impl/encode! (+ p (* i s)) ek (nth items i) wb)))))))

(defn- pull-live! [refs]
  (doseq [{:keys [box last] :as r} refs]
    (let [v (ref-read r)]
      (when-not (= v @last)
        (vreset! last v)
        (n/defer! #(reset! box v))))))

;; ---- what a call reports

(defn- event-key [prop single?]
  (if single? :on-change (keyword (str "on-" (name prop) "-change"))))

(defn- report-cell! [node {:keys [prop growable]} single?]
  (when growable
    (let [cnt (ffi/read (n/cell-ptr node (count-key prop)) :int)]
      (when-not (= cnt (n/cell-n node prop)) (n/set-cell-n! node prop cnt))))
  (let [v (n/cell-change node prop)]
    (when-not (= v ::n/same)
      (n/fire! node (event-key prop single?) v)
      (when (and (= prop :open) (false? v)) (n/fire! node :on-close)))))

(defn- reporter
  "(fn [r]) run after each call with C's return value r: fires the bool
  return as :on-click, CUI_SUBMITTED as :on-submit, any other return as
  :on-result when it changed since the last frame, and :on-change for each
  out-parameter cell CUI edited. Reading a text cell copies the string, so
  it is read only when the return says it may have changed, where it can."
  [node p {:keys [ret cells]} dst conditional]
  (let [[rk rt] ret
        single? (= 1 (count cells))
        text? #(= [:text] (:kind %))
        texts (filterv text? cells)
        others (filterv (complement text?) cells)
        changed-off (when (= rk :struct) (get-in impl/structs [rt :by-key :changed :off]))
        text-dirty? (case rk
                      :flags #(not (zero? (bit-and (long %) changed-bit)))
                      :bool #(boolean %)
                      :struct (if changed-off (fn [_] (ffi/read (+ dst changed-off) :bool)) (constantly true))
                      (constantly true))
        result? (and (contains? p :on-result) (#{:plain :enum :struct :flags} rk))
        last-result (volatile! ::none)]
    (fn [r]
      (when (and (= rk :bool) r (not conditional)) (n/fire! node :on-click))
      (when (and (= rk :flags) (not (zero? (bit-and (long r) submitted-bit))) (seq texts))
        (n/fire! node :on-submit (n/read-cell node (:prop (first texts)))))
      (when result?
        (let [v (case rk
                  :struct (n/decode dst [:struct rt])
                  :enum (impl/enum-key rt r)
                  r)]
          (when-not (= v @last-result)
            (when-not (= ::none @last-result) (n/fire! node :on-result v))
            (vreset! last-result v))))
      (dotimes [i (count others)] (report-cell! node (nth others i) single?))
      (when (and (seq texts) (text-dirty? r))
        (dotimes [i (count texts)] (report-cell! node (nth texts i) single?))))))

(defn- call-and-report [node p entry f conditional]
  (let [wb (volatile! [])
        {:keys [args cells ret]} entry
        args' (build-args node p wb args (arg-props args cells))
        dst (when (= :struct (first ret)) (impl/alloc (second ret)))
        call (call-fn f dst args')
        report (reporter node p entry dst conditional)
        refs (live-refs wb)]
    (if (seq refs)
      (fn [ui] (push-live! refs) (let [r (call ui)] (report r) (pull-live! refs) r))
      (fn [ui] (let [r (call ui)] (report r) r)))))

(defn compile-entry
  "A widget spec from a glimmer-cui.tags entry; `container?` for an entry of
  tags/containers. A widget that takes an id gets its own CUI id scope unless
  the :id prop is given, so CUI calls naming that id (popover-open,
  code-editor-goto) can reach it from with-ui or :cui/immediate."
  [entry container?]
  (let [id? (some (fn [[_ prop]] (= prop :id)) (:args entry))
        scope? (if id? #(not (contains? % :id)) (constantly true))]
    (if container?
      (let [conditional (:conditional entry)
            begin (:begin entry) end (:end entry)]
        {:kind :container
         :conditional conditional
         :cells (:cells entry)
         :scope? scope?
         :compile (fn [node p]
                    (let [b (call-and-report node p entry begin conditional)]
                      {:begin (if conditional (fn [ui] (b ui)) (fn [ui] (b ui) true))
                       :end end}))})
      {:kind :leaf
       :cells (:cells entry)
       :scope? scope?
       :compile (fn [node p] (call-and-report node p entry (:fn entry) false))})))

(def generated
  "Every CUI component the generator found, compiled from glimmer-cui.tags."
  (merge (update-vals tags/leaves #(compile-entry % false))
         (update-vals tags/containers #(compile-entry % true))))

(def ^:private hand-written
  {;; ---- layout
   :box     (box-container raw/cui_box_begin :cui_box)
   :row     (box-container raw/cui_row_begin :cui_box)
   :column  (box-container raw/cui_column_begin :cui_box)
   :card    {:kind :container
             :compile (fn [_ p] (let [o (opts-ptr :cui_card_opts p #{})]
                                  {:begin (fn [ui] (raw/cui_card_begin ui o)) :end raw/cui_card_end}))}
   :scroll  {:kind :container
             :compile (fn [_ p] (let [o (opts-ptr :cui_scroll_opts p #{})]
                                  {:begin (fn [ui] (raw/cui_scroll_begin ui o)) :end raw/cui_scroll_end}))}
   :spacer    {:kind :leaf :compile (fn [_ _] raw/cui_spacer)}
   :separator {:kind :leaf :compile (fn [_ _] raw/cui_separator)}

   ;; ---- text
   :text      (leaf :text :cui_text_opts raw/cui_text_ex)
   :label     {:kind :leaf :compile (fn [_ p] (let [a (s (:label p))] (fn [ui] (raw/cui_label ui a))))}
   :secondary {:kind :leaf :compile (fn [_ p] (let [a (s (:text p))] (fn [ui] (raw/cui_secondary ui a))))}
   :heading   {:kind :leaf :compile (fn [_ p] (let [a (s (:text p)) lv (long (or (:level p) 2))]
                                                (fn [ui] (raw/cui_heading ui a lv))))}
   :badge     (leaf :text :cui_badge_opts raw/cui_badge_ex)
   :progress  {:kind :leaf
               :compile (fn [_ p] (let [v (double (or (:value p) 0)) o (opts-ptr :cui_progress_opts p #{:value})]
                                    (fn [ui] (raw/cui_progress_ex ui v o))))}

   ;; ---- controls
   :button  {:kind :leaf
             :compile (fn [node p]
                        (let [l (s (:label p)) o (opts-ptr :cui_button_opts p #{:label})]
                          (fn [ui] (when (raw/cui_button_ex ui l o) (n/fire! node :on-click)))))}
   :switch   (check-widget raw/cui_switch_ex)
   :checkbox (check-widget raw/cui_checkbox_ex)
   :slider  {:kind :leaf
             :cells [{:prop :value :kind [:scalar :float]}]
             :compile (fn [node p]
                        (let [l (s (:label p)) lo (double (or (:min p) 0)) hi (double (or (:max p) 1))
                              o (opts-ptr :cui_slider_opts p #{:label :value :min :max}) c (n/cell-ptr node :value)]
                          (fn [ui]
                            (when (raw/cui_slider_ex ui l c lo hi o)
                              (n/fire! node :on-change (n/read-cell node :value))))))}
   :input   {:kind :leaf
             :cells [{:prop :value :kind [:text]}]
             :compile (fn [node p]
                        ;; the node's own id scope makes a fixed id unique
                        (let [id (s (or (:id p) "input"))
                              o (opts-ptr :cui_input_opts p #{:id :value :max-length})
                              c (n/cell-ptr node :value) cap (n/cell-cap node :value)]
                          (fn [ui]
                            (let [r (raw/cui_input_ex ui id c cap o)]
                              (when-not (zero? r)
                                (let [v (n/read-cell node :value)]
                                  (when-not (zero? (bit-and r changed-bit))
                                    (n/note-cell-val! node :value v)
                                    (n/fire! node :on-change v))
                                  (when-not (zero? (bit-and r submitted-bit))
                                    (n/fire! node :on-submit v))))))))}

   ;; ---- overlays
   :dialog  {:kind :container
             :conditional true
             :cells [{:prop :open :kind [:bool]}]
             :compile (fn [node p]
                        (let [t (s (:title p)) o (opts-ptr :cui_dialog_opts p #{:title :open}) c (n/cell-ptr node :open)]
                          {:begin (fn [ui]
                                    (let [shown (raw/cui_dialog_begin ui t c o)
                                          v (ffi/read c :bool)]
                                      ;; CUI clears *open on Escape, the scrim or the x
                                      (when (and (not v) (n/last-cell-val node :open))
                                        (n/note-cell-val! node :open false)
                                        (n/fire! node :on-close))
                                      shown))
                           :end raw/cui_dialog_end}))}
   :dialog-footer {:kind :container
                   :compile (fn [_ _] {:begin (fn [ui] (raw/cui_dialog_footer_begin ui) true)
                                       :end raw/cui_dialog_footer_end})}

   ;; ---- escape hatch: an immediate-mode island
   ;; [:cui/immediate {:render (fn [ui] ...)}] calls render every frame with the
   ;; ui handle, inside a frame arena, so any cui.core call works there: a
   ;; widget this table lacks, a terminal, a canvas. Its cost is per frame.
   :cui/immediate {:kind :leaf
                   ;; the one replay that runs user code each frame, so the one
                   ;; that catches: a throw is reported once per message and the
                   ;; frame goes on with CUI's containers still balanced
                   :compile (fn [node _]
                              (let [last-error (volatile! nil)]
                                (fn [ui]
                                  (when-let [f (:render @(:props node))]
                                    (try (f ui)
                                         (catch Throwable e
                                           (when (not= (ex-message e) @last-error)
                                             (vreset! last-error (ex-message e))
                                             (binding [*out* *err*]
                                               (println "glimmer-cui: :cui/immediate render threw:" (ex-message e))))))))))}})

(def specs
  "Tag -> widget spec: the hand-written ones over the generated ones."
  (merge generated hand-written))

(defn spec-for [tag]
  (or (get specs tag)
      (throw (ex-info (str "glimmer-cui: unknown tag " tag)
                      {:tag tag :valid (sort (map str (keys specs)))}))))
