(ns glimmer-cui.protocols
  "Components whose CUI protocol a plain begin, children, end replay can't
  express, as containers that replay themselves ({:replay f} from compile).

  Rows decided mid-frame. A virtual list, data grid or tree table builds only
  the rows in view, and only CUI knows which those are, once its begin call
  has run. These tags take a :row function instead of children:

      [:data-grid {:columns cols :row-count (count people)
                   :row (fn [i] (let [p (nth people i)]
                                  (list [:data-grid-cell-text {:text (:name p)}]
                                        [:data-grid-cell-text {:text (:email p)}])))}]

  Each frame the node asks CUI for the rows in view, mounts a glimmer subtree
  [row-view i] for every row that came into view, walks them, and unmounts the
  rows that left. A row is an ordinary reactive component: it re-renders when
  a ratom it read changes, or when the :row function itself changes (it is
  held in a ratom). A row function may return one element, a seq of them, or
  nil.

  Numbered items. Reorderable lists and card stacks number their items; the
  node wraps each child i in CUI's item begin/end, so children are written
  plainly in order.

  Results at the end. Kanban boards, swipe items, reorderable lists and card
  stacks report what happened from their end call; the node editor reports
  links and deletions through queries after it. Each becomes an event."
  (:require [jolt.ffi :as ffi]
            [cui.impl :as impl]
            [cui.raw :as raw]
            [glimmer.core :as g]
            [glimmer.ratom :as r]
            [glimmer-cui.node :as n]
            [glimmer-cui.widgets :as w]))

(defn- id-scope? [props] (not (contains? props :id)))

(defn- int-pair
  "Two ints of scratch in the node's encoding arena (bound during compile)."
  []
  (let [p (ffi/alloc (impl/arena) 8)] [p (+ p 4)]))

(defn- report! [node k ev]
  (let [v (n/cell-change node k)]
    (when-not (= v ::n/same) (n/fire! node ev v))))

;; ---------------------------------------------------------------------------
;; rows mounted on demand

(defn- row-view
  "A row's component: the node's current :row function applied to row i."
  [row-fn i]
  (let [f @row-fn
        h (when f (f i))]
    (cond (seq? h) (into [:group] h)
          (nil? h) [:group]
          :else h)))

(defn- row-fn-atom [node]
  (or (:row-fn @(:state node))
      (let [a (r/atom nil)] (vswap! (:state node) assoc :row-fn a) a)))

(defn- set-row-fn!
  "Hand the :row prop to the mounted rows, which re-render with it."
  [node p]
  (let [a (row-fn-atom node)]
    (when-not (identical? @a (:row p)) (reset! a (:row p)))))

(defn- row-root
  "The root of row k's subtree, mounting the row when it is new."
  [node k]
  (or (get-in @(:state node) [:rows k :root])
      (let [root (n/make :cui/row {:kind :container})
            inst (g/mount root :cui/row [row-view (row-fn-atom node) k])]
        (vswap! (:state node) assoc-in [:rows k] {:root root :inst inst})
        root)))

(defn- walk-row! [ui node k]
  (n/walk-children! ui (row-root node k)))

(defn- unmount-row! [{:keys [root inst]}]
  (g/unmount! inst root :cui/row))

(defn- prune-rows!
  "Unmount the rows `keep?` rejects: those that left the view this frame."
  [node keep?]
  (let [rows (:rows @(:state node))
        gone (remove (fn [[k _]] (keep? k)) rows)]
    (when (seq gone)
      (doseq [[_ row] gone] (unmount-row! row))
      (vswap! (:state node) update :rows #(apply dissoc % (map first gone))))))

(defn- unmount-rows! [node]
  (doseq [[_ row] (:rows @(:state node))] (unmount-row! row))
  (vswap! (:state node) assoc :rows {}))

(defn mounted-rows
  "The row keys a virtual list, data grid or tree table has mounted."
  [node]
  (sort (keys (:rows @(:state node)))))

(defn- row-container [compile]
  {:kind :container :scope? id-scope? :dispose unmount-rows! :compile compile})

(def virtual-list
  "[:virtual-list {:count n :row-height 32 :height 300 :row (fn [i] ...)}]"
  (row-container
   (fn [node p]
     (set-row-fn! node p)
     (let [p (merge {:row-height 32 :height 300} p)
           {[id cnt rh h] :args} (w/encode-args node p [[:str :id] [:val :count [:scalar :int]]
                                                        [:val :row-height [:scalar :float]]
                                                        [:val :height [:struct :cui_size]]]
                                                #{:row})
           [fp lp] (int-pair)]
       {:replay (fn [ui]
                  (raw/cui_virtual_list_begin ui id cnt rh h fp lp)
                  (let [a (ffi/read fp :int) b (ffi/read lp :int)]
                    (loop [i a] (when (< i b) (walk-row! ui node i) (recur (inc i))))
                    (prune-rows! node #(and (>= % a) (< % b))))
                  (raw/cui_virtual_list_end ui))}))))

(def data-grid
  "[:data-grid {:columns [{:title ...}] :row-count n :row (fn [i] cells)
                :on-row-click (fn [i])}] with cui_grid_opts props; cells are
  :data-grid-cell-text, :data-grid-cell-edit, or :data-grid-cell followed by
  components."
  (row-container
   (fn [node p]
     (set-row-fn! node p)
     (let [{[id cols ncols rows opts] :args refs :refs}
           (w/encode-args node p [[:str :id] [:vec :columns [:struct :cui_grid_column]]
                                  [:val :row-count [:scalar :int]] [:opts :cui_grid_opts]]
                          #{:row})
           [fp lp] (int-pair)]
       {:replay (w/with-live
                  refs
                  (fn [ui]
                    (when (raw/cui_data_grid_begin_ex ui id cols ncols rows fp lp opts)
                      (let [a (ffi/read fp :int) b (ffi/read lp :int)]
                        (loop [i a]
                          (when (< i b)
                            (when (raw/cui_data_grid_row ui i) (n/fire! node :on-row-click i))
                            (walk-row! ui node i)
                            (recur (inc i))))
                        (prune-rows! node #(and (>= % a) (< % b))))
                      (raw/cui_data_grid_end ui))))}))))

(defn- read-ints [p n] (mapv #(ffi/read p :int (* 4 %)) (range n)))

(def tree-table
  "[:tree-table {:columns [...] :nodes [{:parent -1 :first-child 1
                 :next-sibling -1 :flags 1} ...] :row (fn [node-index] cells)}]
  A row starts with [:tree-table-name {:label .. :icon ..}]. CUI sets the open
  and selected flags in its copy of :nodes: :on-change gets the new vector.
  Events: :on-activate, :on-expand, :on-collapse (a node index), :on-load
  (node indices to load children for), :on-sort (re-sort by your :sort atom)."
  (assoc (row-container
          (fn [node p]
            (set-row-fn! node p)
            (let [{[id cols ncols np nn opts] :args refs :refs}
                  (w/encode-args node p [[:str :id] [:vec :columns [:struct :cui_grid_column]]
                                         [:cell :nodes [:struct :cui_tree_node] :count]
                                         [:opts :cui_tree_table_opts]]
                                 #{:row})
                  ev (impl/alloc :cui_tree_table_events)
                  seen (volatile! #{})]
              {:replay
               (w/with-live
                 refs
                 (fn [ui]
                   (raw/cui_tree_table_begin_ex ui id cols ncols np nn opts)
                   (vreset! seen #{})
                   (loop []
                     (let [k (raw/cui_tree_table_row ui)]
                       (when (>= k 0)
                         (vswap! seen conj k)
                         (walk-row! ui node k)
                         (recur))))
                   (let [in-view @seen] (prune-rows! node in-view))
                   (raw/cui_tree_table_end ev ui)
                   (let [{:keys [activated expanded collapsed load load-count sort-changed]}
                         (n/decode ev [:struct :cui_tree_table_events])]
                     (when (>= activated 0) (n/fire! node :on-activate activated))
                     (when (>= expanded 0) (n/fire! node :on-expand expanded))
                     (when (>= collapsed 0) (n/fire! node :on-collapse collapsed))
                     (when (pos? load-count) (n/fire! node :on-load (read-ints load load-count)))
                     (when sort-changed (n/fire! node :on-sort)))
                   (report! node :nodes :on-change)))})))
         :cells [{:prop :nodes :kind [:struct :cui_tree_node]}]))

;; ---------------------------------------------------------------------------
;; kanban

(def kanban
  "[:kanban {:on-move (fn [{:keys [card from-column to-column to-index]}])}
     [:kanban-column {:title \"Todo\"} [:kanban-card {:card-id 7} ...] ...] ...]
  Columns are numbered in order. A drop only reports: move the card in your
  data."
  {:kind :container
   :scope? id-scope?
   :compile (fn [node p]
              (let [{[id] :args} (w/encode-args node p [[:str :id]])
                    mv (impl/alloc :cui_kanban_move)]
                {:replay (fn [ui]
                           (raw/cui_kanban_begin ui id)
                           (n/walk-children! ui node)
                           (when (raw/cui_kanban_end ui mv)
                             (n/fire! node :on-move (n/decode mv [:struct :cui_kanban_move]))))}))})

(def kanban-column
  "[:kanban-column {:title .. :dot .. :limit .. :on-add (fn [])} cards...]
  :card-count defaults to the number of cards."
  {:kind :container
   :compile (fn [node p]
              (let [{[title opts] :args} (w/encode-args node p [[:str :title] [:opts :cui_kanban_column_opts]]
                                                        #{:card-count})
                    cc (:card-count p)]
                {:replay (fn [ui]
                           (when (raw/cui_kanban_column_begin_ex ui title (long (or cc (count @(:children node)))) opts)
                             (n/fire! node :on-add))
                           (n/walk-children! ui node)
                           (raw/cui_kanban_column_end ui))}))})

(def kanban-card
  "[:kanban-card {:card-id 7 :on-click (fn [])} content...]"
  {:kind :container
   :compile (fn [node p]
              (let [card (long (or (:card-id p) (:id node)))]
                {:replay (fn [ui]
                           (when (raw/cui_kanban_card_begin ui card) (n/fire! node :on-click))
                           (n/walk-children! ui node)
                           (raw/cui_kanban_card_end ui))}))})

;; ---------------------------------------------------------------------------
;; swipe items, reorderable lists, card stacks

(def swipe-item
  "[:swipe-item {:left [{:icon :archive :label \"Archive\" :tone :success}]
                 :right [...] :on-action (fn [{:keys [side index]}])} content]"
  {:kind :container
   :scope? id-scope?
   :compile (fn [node p]
              (let [{[id lp ln rp rn] :args}
                    (w/encode-args node p [[:str :id] [:vec :left [:struct :cui_swipe_action]]
                                           [:vec :right [:struct :cui_swipe_action]]])]
                {:replay (fn [ui]
                           (raw/cui_swipe_item_begin ui id lp ln rp rn)
                           (n/walk-children! ui node)
                           (let [r (raw/cui_swipe_item_end ui)]
                             (when (>= r 0)
                               (n/fire! node :on-action (if (>= r 100)
                                                          {:side :right :index (- r 100)}
                                                          {:side :left :index r})))))}))})

(def reorder
  "[:reorder {:on-reorder (fn [order])} items...]: drag items to reorder.
  Items are written in display order; on a drop :on-reorder gets the new
  order as indices into the current one ([1 0 2] swaps the first two)."
  {:kind :container
   :scope? id-scope?
   :compile (fn [node p]
              (let [{[id] :args} (w/encode-args node p [[:str :id]])]
                {:replay (fn [ui]
                           (let [k (count @(:children node))
                                 order (ffi/alloc (impl/arena) (* 4 (max 1 k)))]
                             (dotimes [i k] (ffi/write order :int i (* 4 i)))
                             (raw/cui_reorder_begin ui id order k)
                             (n/walk-children-with! ui node
                                                    (fn [ui i] (raw/cui_reorder_item_begin ui i))
                                                    (fn [ui _] (raw/cui_reorder_item_end ui)))
                             (when (raw/cui_reorder_end ui)
                               (n/fire! node :on-reorder (read-ints order k)))))}))})

(def card-stack
  "[:card-stack {:height 240 :on-swipe (fn [side])} cards...]: the first
  child is the top card; :on-swipe gets :left or :right when it is flicked."
  {:kind :container
   :scope? id-scope?
   :compile (fn [node p]
              (let [{[id opts] :args} (w/encode-args node p [[:str :id] [:opts :cui_card_stack_opts]])]
                {:replay (fn [ui]
                           (raw/cui_card_stack_begin ui id (count @(:children node)) opts)
                           (n/walk-children-with! ui node
                                                  (fn [ui i] (raw/cui_card_stack_item_begin ui i))
                                                  (fn [ui _] (raw/cui_card_stack_item_end ui)))
                           (let [r (raw/cui_card_stack_end ui)]
                             (when-not (zero? r)
                               (n/fire! node :on-swipe (if (neg? r) :left :right)))))}))})

;; ---------------------------------------------------------------------------
;; node editor

(def node-editor
  "[:node-editor {:view {:x 0 :y 0 :zoom 1} :height 480
                  :on-link-create (fn [[from-pin to-pin]]) :on-link-remove (fn [link-id])
                  :on-node-delete (fn [node-id]) :on-view-change (fn [view])}
     [:node {:node-id 1 :x 40 :y 60 :title \"Mix\" :on-move (fn [{:keys [x y]}])}
      [:node-input {:pin-id 10 :label \"A\"}]
      [:node-content [:slider ...]]
      [:node-output {:pin-id 11 :label \"Out\"}]]
     [:node-link {:link-id 1 :from-pin 11 :to-pin 20}]]
  Links and deletions only report: change your data."
  {:kind :container
   :scope? id-scope?
   :cells [{:prop :view :kind [:struct :cui_node_view]}]
   :compile (fn [node p]
              (let [{[id vp opts] :args refs :refs}
                    (w/encode-args node p [[:str :id] [:cell :view [:struct :cui_node_view]]
                                           [:opts :cui_node_editor_opts]])
                    [ap bp] (int-pair)]
                {:replay
                 (w/with-live
                   refs
                   (fn [ui]
                     (when (raw/cui_node_editor_begin_ex ui id vp opts)
                       (n/walk-children! ui node)
                       (raw/cui_node_editor_end ui)
                       ;; what the user did this frame, one event per call
                       (while (raw/cui_node_link_created ui ap bp)
                         (n/fire! node :on-link-create [(ffi/read ap :int) (ffi/read bp :int)]))
                       (while (raw/cui_node_link_removed ui ap)
                         (n/fire! node :on-link-remove (ffi/read ap :int)))
                       (while (raw/cui_node_deleted ui ap)
                         (n/fire! node :on-node-delete (ffi/read ap :int))))
                     (report! node :view :on-view-change)))}))})

(def editor-node
  "[:node {:node-id 1 :x 40 :y 60 :title \"Mix\" :icon .. :width ..
           :on-move (fn [{:keys [x y]}]) :on-select (fn [selected?])} rows...]"
  {:kind :container
   :cells [{:prop :x :kind [:scalar :float]} {:prop :y :kind [:scalar :float]}]
   :compile (fn [node p]
              (let [{[nid xp yp title opts] :args}
                    (w/encode-args node p [[:val :node-id [:scalar :int]]
                                           [:cell :x [:scalar :float]] [:cell :y [:scalar :float]]
                                           [:str :title] [:opts :cui_node_opts]])
                    selected (volatile! false)]
                {:replay (fn [ui]
                           (let [sel (raw/cui_node_begin_ex ui nid xp yp title opts)]
                             (n/walk-children! ui node)
                             (raw/cui_node_end ui)
                             (when-not (= sel @selected)
                               (vreset! selected sel)
                               (n/fire! node :on-select sel)))
                           (let [x (n/cell-change node :x) y (n/cell-change node :y)]
                             (when-not (and (= x ::n/same) (= y ::n/same))
                               (n/fire! node :on-move {:x (n/last-cell-val node :x)
                                                       :y (n/last-cell-val node :y)}))))}))})

;; ---------------------------------------------------------------------------

(def group
  "[:group children...]: no CUI call of its own, only its children. What a
  :row function's seq of cells is wrapped in."
  {:kind :container
   :compile (fn [node _] {:replay (fn [ui] (n/walk-children! ui node))})})

(def specs
  {:virtual-list virtual-list
   :data-grid data-grid
   :tree-table tree-table
   :kanban kanban
   :kanban-column kanban-column
   :kanban-card kanban-card
   :swipe-item swipe-item
   :reorder reorder
   :card-stack card-stack
   :node-editor node-editor
   :node editor-node
   :group group})

(w/register! specs)
