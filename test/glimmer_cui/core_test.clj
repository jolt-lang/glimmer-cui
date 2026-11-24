(ns glimmer-cui.core-test
  "Headless tests: a bare CUI context, synthetic input, no display."
  (:require [clojure.test :refer [deftest is testing]]
            [cui.raw :as raw]
            [glimmer.ratom :as r]
            [glimmer-cui.core :as gc]
            [glimmer-cui.headless :as h]
            [glimmer-cui.node :as n]))

(defn- with-session* [opts hiccup f]
  (let [s (h/open opts)]
    (try (h/mount! s hiccup) (f s) (finally (h/close! s)))))

(defmacro with-session [[s hiccup & [opts]] & body]
  `(with-session* (merge {:width 420 :height 400} ~opts) ~hiccup (fn [~s] ~@body)))

(defmacro counting [& body]
  `(let [st# (atom {})] (binding [gc/*stats* st#] ~@body) @st#))

(defn- tree [node]
  (into [(:tag node)] (map tree @(:children node))))

(defn- ids [node] (mapv :id @(:children node)))

;; ---------------------------------------------------------------------------

(deftest mounts-hiccup-as-nodes
  (with-session [s [(fn [] [:column {:gap 8} [:heading {:text "Hi"}] "plain" [:row [:button {:label "a"}]]])]]
    (h/step! s)
    (is (= [:cui/root [:column [:heading] [:text] [:row [:button]]]] (tree (:root s))))))

(deftest a-change-patches-in-place-and-re-encodes-only-what-changed
  (let [label (r/atom "one")
        static-renders (atom 0)
        static (fn [] (swap! static-renders inc) [:text {:text "static"}])
        app (fn [] [:column [:text {:text @label}] [static] [:button {:label "b"}]])]
    (with-session [s [app]]
      (h/step! s)
      (let [col (first @(:children (:root s)))
            before (ids col)
            st (counting (reset! label "two") (h/step! s))]
        (is (= before (ids col)) "same nodes, not recreated")
        (is (= 1 (:encodes st)) "only the text whose prop changed is encoded")
        (is (= 1 @static-renders) "an unaffected child component is not re-run")))))

(deftest idle-frames-run-no-component-code-and-encode-nothing
  (let [renders (atom 0)
        n (r/atom 0)
        app (fn [] (swap! renders inc)
              [:column (for [i (range 50)] ^{:key i} [:button {:label (str "b" i " " @n)}])])]
    (with-session [s [app]]
      (h/step! s 2)
      (let [st (counting (h/step! s 30))]
        (is (= 1 @renders))
        (is (= 30 (:frames st)))
        (is (nil? (:encodes st)))
        (is (zero? (:work st)))))))

(deftest only-the-component-that-read-a-ratom-re-renders
  (let [a (r/atom 0) b (r/atom 0) ra (atom 0) rb (atom 0)
        ca (fn [] (swap! ra inc) [:text {:text (str "a" @a)}])
        cb (fn [] (swap! rb inc) [:text {:text (str "b" @b)}])]
    (with-session [s [(fn [] [:column [ca] [cb]])]]
      (h/step! s)
      (swap! a inc)
      (h/step! s)
      (is (= [2 1] [@ra @rb])))))

(deftest button-click-fires-on-click-once-after-the-walk
  (let [clicks (r/atom 0) btn (atom nil)]
    (with-session [s [(fn [] [:column {:pad 20}
                              [:button {:label "go" :ref #(reset! btn %) :on-click #(swap! clicks inc)}]
                              [:text {:text (str @clicks)}]])]]
      (h/step! s 2)
      (h/click! s (gc/rect @btn))
      (h/step! s 2)
      (is (= 1 @clicks)))))

(deftest switch-edits-reach-on-change-and-survive-unrelated-re-renders
  (let [other (r/atom 0) seen (atom []) sw (atom nil)
        ;; :checked is always true: the handler does not feed back into it
        app (fn [] [:column {:pad 20}
                    [:switch {:label "s" :checked true :ref #(reset! sw %) :on-change #(swap! seen conj %)}]
                    [:text {:text (str @other)}]])]
    (with-session [s [app]]
      (h/step! s 2)
      (h/click! s (gc/rect @sw))
      (is (= [false] @seen))
      (testing "a re-render that repeats the old prop leaves the user's edit alone"
        (swap! other inc)
        (h/step! s 2)
        (is (false? (n/read-cell @sw)))))))

(deftest a-changed-prop-overwrites-the-cell
  (let [on (r/atom true) sw (atom nil)]
    (with-session [s [(fn [] [:switch {:label "s" :checked @on :ref #(reset! sw %)}])]]
      (h/step! s)
      (reset! on false)
      (h/step! s)
      (is (false? (n/read-cell @sw))))))

(deftest input-typing-and-enter
  (let [text (r/atom "hi") submitted (atom nil) in (atom nil)]
    (with-session [s [(fn [] [:column {:pad 20}
                              [:input {:value @text :placeholder "Name" :width :fill :ref #(reset! in %)
                                       :on-change #(reset! text %) :on-submit #(reset! submitted %)}]])]]
      (h/step! s 2)
      (h/click! s (gc/rect @in))
      (h/type-text! s "!")
      (is (= "hi!" @text))
      (h/key! s raw/CUI_KEY_ENTER)
      (is (= "hi!" @submitted)))))

(deftest dialog-shows-children-only-while-open-and-reports-close
  (let [open (r/atom false) closed (atom 0) body (atom nil)
        app (fn [] [:column
                    [:dialog {:title "Sure?" :open @open
                              :on-close #(do (swap! closed inc) (reset! open false))}
                     [:text {:text "body" :ref #(reset! body %)}]]])]
    (with-session [s [app]]
      (h/step! s 2)
      (is (nil? (gc/rect @body)) "not replayed while closed")
      (reset! open true)
      (h/step! s 10)
      (is (some? (gc/rect @body)))
      (h/key! s raw/CUI_KEY_ESCAPE)
      (h/step! s 2)
      (is (= 1 @closed))
      (is (false? @open)))))

(deftest keyed-rows-keep-their-nodes-and-cui-state-across-reorder
  (let [items (r/atom [1 2 3]) refs (atom {}) typed (atom {})
        row (fn [i] [:input {:value "" :ref #(swap! refs assoc i %)
                             :on-change #(swap! typed assoc i %)}])
        app (fn [] [:column {:pad 20 :gap 8 :width 300}
                    (for [i @items] ^{:key i} [row i])])]
    (with-session [s [app]]
      (h/step! s 2)
      (let [nodes (into {} (map (fn [[k v]] [k (:id v)])) @refs)]
        (h/click! s (gc/rect (@refs 1)))
        (h/type-text! s "x")
        (reset! items [3 2 1])
        (h/step! s 2)
        (is (= nodes (into {} (map (fn [[k v]] [k (:id v)])) @refs)) "same node per key")
        (is (= [3 2 1] (map #(some (fn [[k v]] (when (= (:id v) %) k)) @refs)
                            (ids (first @(:children (:root s)))))))
        (testing "focus followed row 1 from first to last: typing still goes to it"
          (h/type-text! s "y")
          (is (= "xy" (@typed 1)))
          (is (= {1 "xy"} @typed)))))))

(deftest removed-nodes-release-their-memory
  (let [show (r/atom true)]
    (with-session [s [(fn [] [:column (when @show [:input {:value "a"}]) [:text {:text "t"}]])]]
      (h/step! s 3)
      (n/reap!)
      (let [before (n/retired-count)]
        (reset! show false)
        (h/step! s 1)
        (is (> (n/retired-count) before) "the input's arena and buffer are retired")
        (h/step! s 3)
        (is (<= (n/retired-count) 2) "and released a couple of frames later")))))

(deftest escape-hatches
  (testing "with-ui runs once, inside a frame"
    (let [calls (atom 0)]
      (with-session [s [(fn [] [:text {:text "x"}])]]
        (h/step! s)
        (gc/with-ui (fn [u] (raw/cui_request_redraw u) (swap! calls inc)))
        (h/step! s 3)
        (is (= 1 @calls)))))
  (testing ":cui/immediate renders every frame, with cui.core available"
    (let [frames (atom 0)]
      (with-session [s [(fn [] [:column [:cui/immediate {:render (fn [u]
                                                                   (swap! frames inc)
                                                                   ((requiring-resolve 'cui.core/textf) u "%d" @frames))}]])]]
        (h/step! s 5)
        (is (= 5 @frames))))))

(deftest unknown-tags-and-options-are-reported
  (is (thrown-with-msg? Exception #"unknown tag :nope"
                        (with-session [s [(fn [] [:nope])]] (h/step! s))))
  (is (thrown-with-msg? Exception #"has no option :bogus"
                        (with-session [s [(fn [] [:button {:label "x" :bogus 1}])]] (h/step! s)))))

(deftest changes-from-other-threads-are-applied-on-the-next-frame
  (let [n (r/atom 0) t (atom nil)]
    (with-session [s [(fn [] [:text {:text (str @n) :ref #(reset! t %)}])]]
      (h/step! s)
      @(future (swap! n inc))
      (is (= {:text "0"} (select-keys @(:props @t) [:text])) "nothing rendered off the UI thread")
      (let [st (counting (h/step! s))]
        (is (= 1 (:work st)))
        (is (= {:text "1"} (select-keys @(:props @t) [:text])))))))
