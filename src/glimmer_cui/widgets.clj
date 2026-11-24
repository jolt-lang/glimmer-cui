(ns glimmer-cui.widgets
  "The tag table: what each hiccup tag replays as.

  A widget spec is a map:

    :kind        :leaf or :container
    :conditional true when a container's children (and its end call) run
                 only while begin returns true, like CUI's dialog
    :cell        {:prop k :type t} for a widget with an out-parameter: prop k
                 seeds a persistent C cell of type :bool, :float or :text
    :compile     (fn [node props] -> replay). Runs once per props change, with
                 the node's encoding arena bound, so cui.impl's encoders
                 allocate there. For a leaf the replay is (fn [ui]); for a
                 container it is {:begin (fn [ui]) :end (fn [ui])}. Replays
                 close over pre-encoded pointers: a frame encodes nothing.

  Props not consumed as positional arguments become the widget's CUI options
  struct, with cui-jolt's conventions (keywords for enums and icons, :fill,
  colors, …). :key, :ref, :on-* handlers and :cui/* keys are never encoded."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [cui.impl :as impl]
            [cui.raw :as raw]
            [glimmer-cui.node :as n]))

(defn- reserved? [k]
  (or (#{:key :ref} k)
      (and (keyword? k) (or (str/starts-with? (name k) "on-") (= "cui" (namespace k))))))

(defn opts-ptr
  "An options struct of type `t` encoded from `props`, leaving out `consumed`
  keys and reserved ones."
  [t props consumed]
  (impl/in-ptr! (volatile! []) [:struct t]
                (reduce-kv (fn [m k v] (if (or (reserved? k) (contains? consumed k)) m (assoc m k v)))
                           {} props)))

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
   :cell {:prop :checked :type :bool}
   :compile (fn [node p]
              (let [l (s (:label p)) o (opts-ptr :cui_check_opts p #{:label :checked}) c (n/cell-ptr node)]
                (fn [ui]
                  (when (cfn ui l c o)
                    (let [v (n/read-cell node)]
                      (vreset! (:cell-val node) v)
                      (n/fire! node :on-change v))))))})

(def specs
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
             :cell {:prop :value :type :float}
             :compile (fn [node p]
                        (let [l (s (:label p)) lo (double (or (:min p) 0)) hi (double (or (:max p) 1))
                              o (opts-ptr :cui_slider_opts p #{:label :value :min :max}) c (n/cell-ptr node)]
                          (fn [ui]
                            (when (raw/cui_slider_ex ui l c lo hi o)
                              (n/fire! node :on-change (n/read-cell node))))))}
   :input   {:kind :leaf
             :cell {:prop :value :type :text}
             :compile (fn [node p]
                        ;; the node's own id scope makes a fixed id unique
                        (let [id (s (or (:id p) "input"))
                              o (opts-ptr :cui_input_opts p #{:id :value :max-length})
                              c (n/cell-ptr node) cap (n/cell-cap node)]
                          (fn [ui]
                            (let [r (raw/cui_input_ex ui id c cap o)]
                              (when-not (zero? r)
                                (let [v (n/read-cell node)]
                                  (when-not (zero? (bit-and r changed-bit))
                                    (vreset! (:cell-val node) v)
                                    (n/fire! node :on-change v))
                                  (when-not (zero? (bit-and r submitted-bit))
                                    (n/fire! node :on-submit v))))))))}

   ;; ---- overlays
   :dialog  {:kind :container
             :conditional true
             :cell {:prop :open :type :bool}
             :compile (fn [node p]
                        (let [t (s (:title p)) o (opts-ptr :cui_dialog_opts p #{:title :open}) c (n/cell-ptr node)]
                          {:begin (fn [ui]
                                    (let [shown (raw/cui_dialog_begin ui t c o)
                                          v (ffi/read c :bool)]
                                      ;; CUI clears *open on Escape, the scrim or the x
                                      (when (and (not v) @(:cell-val node))
                                        (vreset! (:cell-val node) false)
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

(defn spec-for [tag]
  (or (get specs tag)
      (throw (ex-info (str "glimmer-cui: unknown tag " tag)
                      {:tag tag :valid (sort (map str (keys specs)))}))))
