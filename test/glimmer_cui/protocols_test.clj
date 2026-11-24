(ns glimmer-cui.protocols-test
  "Headless tests for the components with custom replays: rows mounted on
  demand (virtual list, data grid, tree table) and protocols that report from
  their end call (kanban, reorder, swipe item, card stack, node editor). Click
  and drag positions come from CUI's layout at these window sizes."
  (:require [clojure.test :refer [deftest is testing]]
            [cui.raw :as raw]
            [glimmer.ratom :as r]
            [glimmer-cui.core]
            [glimmer-cui.headless :as h]
            [glimmer-cui.node :as n]
            [glimmer-cui.protocols :as pr]))

(defn- with-session* [opts hiccup f]
  (let [s (h/open opts)]
    (try (h/mount! s hiccup) (f s) (finally (h/close! s)))))

(defmacro with-session [[s hiccup & [opts]] & body]
  `(with-session* (merge {:width 420 :height 400} ~opts) ~hiccup (fn [~s] ~@body)))

;; ---------------------------------------------------------------------------

(deftest virtual-list-mounts-only-the-rows-in-view
  (let [vl (atom nil) label (r/atom "row") renders (atom 0)
        app (fn [] [:column {:pad 20}
                    [:virtual-list {:count 10000 :row-height 32 :height 240 :ref #(reset! vl %)
                                    :row (fn [i]
                                           (swap! renders inc)
                                           [:row {:height 32} [:text {:text (str @label " " i)}]])}]])]
    (with-session [s [app]]
      (h/step! s 3)
      (let [rows (pr/mounted-rows @vl)]
        (is (= 0 (first rows)))
        (is (< (count rows) 15) "a screenful, not 10000"))
      (testing "scrolling mounts the rows that come into view and unmounts the rest"
        (h/scroll! s [200 120] -2000)
        (let [rows (pr/mounted-rows @vl)]
          (is (> (first rows) 30))
          (is (< (count rows) 15))))
      (testing "a ratom a row read re-renders the mounted rows only"
        (let [mounted (count (pr/mounted-rows @vl))
              before @renders]
          (reset! label "item")
          (h/step! s 2)
          (is (= mounted (- @renders before)))))
      (testing "unmounting the list unmounts its rows"
        (let [root @vl]
          (n/dispose! root)
          (is (empty? (pr/mounted-rows root))))))))

(deftest data-grid-rows-cells-selection-and-edits
  (let [people (r/atom (vec (for [i (range 500)] {:name (str "Person " i) :email (str "p" i "@x.org")})))
        sel (atom (vec (repeat 500 false)))
        ev (atom [])
        app (fn [] [:column {:pad 20}
                    [:data-grid {:columns [{:title "Name" :width 200} {:title "Email" :width 220}]
                                 :row-count (count @people) :height 300 :selected sel
                                 :on-row-click #(swap! ev conj [:click %])
                                 :row (fn [i]
                                        (let [p (nth @people i)]
                                          (list [:data-grid-cell-edit
                                                 {:value (:name p)
                                                  :on-change (fn [v] (swap! people assoc-in [i :name] v))}]
                                                [:data-grid-cell-text {:text (:email p)}])))}]])]
    (with-session [s [app] {:width 520}]
      (h/step! s 3)
      (h/click! s {:x 300 :y 150 :w 20 :h 20})        ; row 2's email
      (is (= [[:click 2]] @ev))
      (h/click! s {:x 32 :y 110 :w 20 :h 20})         ; row 1's checkbox
      (h/step! s 2)
      (is (= [1 2] (keep-indexed #(when %2 %1) @sel)) "the :selected atom follows the grid")
      (testing "an edit cell commits to :on-change"
        (h/move! s [100 80]) (h/step! s)
        (dotimes [_ 2]
          (raw/cui_input_mouse_button (:ui s) 0 true) (h/step! s)
          (raw/cui_input_mouse_button (:ui s) 0 false) (h/step! s))
        (h/key! s raw/CUI_KEY_END)
        (h/type-text! s "!")
        (h/key! s raw/CUI_KEY_ENTER)
        (h/step! s 2)
        (is (= "Person 0!" (:name (first @people))))))))

(deftest tree-table-expands-and-mounts-children
  ;; 0 src (open) -> 1 core.clj, 2 ui (closed, has children) -> 3 button.clj; 4 README
  (let [nodes (r/atom [{:parent -1 :first-child 1 :next-sibling 4 :flags 3}
                       {:parent 0 :first-child -1 :next-sibling 2 :flags 0}
                       {:parent 0 :first-child 3 :next-sibling -1 :flags 1}
                       {:parent 2 :first-child -1 :next-sibling -1 :flags 0}
                       {:parent -1 :first-child -1 :next-sibling -1 :flags 0}])
        names ["src" "core.clj" "ui" "button.clj" "README.md"]
        ev (atom []) tt (atom nil)
        app (fn [] [:column {:pad 20}
                    [:tree-table {:columns [{:title "Name" :width 260} {:title "Kind"}]
                                  :nodes @nodes :height 300 :ref #(reset! tt %)
                                  :on-change #(do (swap! ev conj [:flags (mapv :flags %)]) (reset! nodes %))
                                  :on-expand #(swap! ev conj [:expand %])
                                  :row (fn [k] (list [:tree-table-name {:label (names k)}]
                                                     [:data-grid-cell-text {:text "x"}]))}]])]
    (with-session [s [app] {:width 520}]
      (h/step! s 3)
      (is (= [0 1 2 4] (pr/mounted-rows @tt)) "button.clj is inside the closed ui")
      (h/click! s {:x 50 :y 132 :w 16 :h 16})         ; ui's chevron
      (h/step! s 3)
      (is (= [[:expand 2] [:flags [3 0 3 0 0]]] @ev))
      (is (= [0 1 2 3 4] (pr/mounted-rows @tt))))))

(deftest kanban-reports-moves-and-adds
  (let [ev (atom [])
        app (fn [] [:column {:pad 20}
                    [:kanban {:on-move #(swap! ev conj %)}
                     [:kanban-column {:title "Todo" :on-add #(swap! ev conj :add)}
                      [:kanban-card {:card-id 1} [:text {:text "Write docs"}]]
                      [:kanban-card {:card-id 2} [:text {:text "Fix bug"}]]]
                     [:kanban-column {:title "Done"}
                      [:kanban-card {:card-id 3} [:text {:text "Ship"}]]]]])]
    (with-session [s [app] {:width 620}]
      (h/step! s 3)
      (h/drag! s [100 142] [450 125])                 ; "Fix bug" below "Ship"
      (h/click! s {:x 268 :y 38 :w 16 :h 16})         ; Todo's +
      (is (= [{:card 2 :from-column 0 :to-column 1 :to-index 1} :add] @ev)))))

(deftest reorder-swipe-and-card-stack
  (let [ev (atom []) items (r/atom ["A" "B" "C"])
        app (fn [] [:column {:pad 20 :gap 20}
                    [:reorder {:on-reorder #(do (swap! ev conj [:order %]) (swap! items (fn [v] (mapv v %))))}
                     (for [x @items] ^{:key x} [:row {:pad 8 :width :fill} [:text {:text x}]])]
                    [:swipe-item {:left [{:icon :archive :label "Archive"}]
                                  :right [{:icon :trash-2 :label "Delete"}]
                                  :on-action #(swap! ev conj [:swipe %])}
                     [:row {:pad 12 :width :fill} [:text {:text "Swipe me"}]]]
                    [:card-stack {:height 120 :on-swipe #(swap! ev conj [:stack %])}
                     [:card {:pad 16} [:text {:text "Top"}]]
                     [:card {:pad 16} [:text {:text "Second"}]]]])]
    (with-session [s [app] {:height 500}]
      (h/step! s 3)
      (h/drag! s [36 47] [36 190])                    ; A's handle below C
      (is (= [[:order [1 2 0]]] @ev))
      (is (= ["B" "C" "A"] @items))
      (h/drag! s [300 248] [10 248] :steps 6)         ; a long swipe left
      (is (= [:swipe {:side :right :index 0}] (last @ev)))
      (h/drag! s [180 330] [420 330] :steps 4)        ; flick the top card
      (h/step! s 30)                                  ; reported once it has flown off
      (is (= [:stack :right] (last @ev))))))

(deftest node-editor-links-and-moves
  (let [ev (atom []) pos (r/atom {1 [20 40] 2 [260 60]}) links (r/atom [])
        app (fn [] [:column {:pad 20}
                    (into [:node-editor {:height 340
                                         :on-link-create #(do (swap! ev conj [:link %]) (swap! links conj %))}
                           [:node {:node-id 1 :x (first (@pos 1)) :y (second (@pos 1)) :title "Source"
                                   :on-move #(swap! pos assoc 1 [(:x %) (:y %)])}
                            [:node-output {:pin-id 11 :label "Out"}]]
                           [:node {:node-id 2 :x (first (@pos 2)) :y (second (@pos 2)) :title "Sink"}
                            [:node-input {:pin-id 21 :label "In"}]
                            [:node-content [:text {:text "content"}]]]]
                          (map-indexed (fn [i [a b]] [:node-link {:link-id i :from-pin a :to-pin b}]) @links))])]
    (with-session [s [app] {:width 560 :height 420}]
      (h/step! s 3)
      (h/drag! s [224 113] [280 133])                 ; Out to In
      (is (= [[:link [11 21]]] @ev))
      (h/drag! s [100 77] [100 237])                  ; Source's title, 160 down
      (is (= [20.0 200.0] (@pos 1)) "the move is fed back as props without fighting the drag"))))
