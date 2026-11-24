# glimmer-cui

A [glimmer](../glimmer) backend for [CUI](https://github.com/yappologistic/CUI):
reagent-style components and ratoms over CUI's native immediate-mode widgets.
It builds on [cui-jolt](../../jlt-commons/cui-jolt)'s binding.

```clojure
(ns hello
  (:require [glimmer.ratom :as r]
            [glimmer.core :as g]
            [glimmer-cui.core]))          ; installs the backend

(defn counter []
  (let [clicks (r/atom 0)]
    (fn []
      [:column {:pad 32 :gap 16}
       [:heading {:text "Hello, CUI" :level 2}]
       [:row {:gap 8}
        [:button {:label "Click me" :variant :primary :on-click #(swap! clicks inc)}]
        [:text {:text (str "Clicked " @clicks " times")}]]])))

(defn -main [& _] (g/run counter :title "Hello" :width 420 :height 300))
```

![settings example](docs/settings.png)

## Why

CUI is immediate-mode all the way down. Every frame it shows (input, hover,
animation, a caret blink) the whole UI has to be submitted again, and nothing
is retained between frames. cui-jolt maps that directly: your frame function
runs every frame, and every widget's options map is converted to a C struct
every frame.

glimmer-cui keeps the widget tree on the Jolt side and controls the
lifecycle:

- **Components re-render only when a ratom they read changes.** glimmer's
  reconciler patches the retained nodes, as it does for GTK.
- **Options are encoded once per props change.** A node's option struct and
  strings live in its own arena, so a frame encodes nothing.
- **A frame is a replay.** Each node holds a closure compiled when its props
  last changed, which calls CUI with pointers that are already built.
- **Out-parameters are node-owned cells.** A switch's `bool`, a slider's
  `float` and an input's text buffer persist across frames. A patch overwrites
  one only when the prop itself changed, so a re-render never clobbers input
  in progress.
- **Events fire after the walk.** Button returns and cell edits are collected
  during the replay and handled afterwards, so a handler's `swap!` never
  reshapes the tree mid-frame.
- **Each leaf gets its own CUI id scope** (`cui_push_id_int` with the node's
  serial). CUI's per-id state (focus, caret, animation) follows the node
  through keyed reorders, and two buttons with the same label don't collide.

## Tags

| Tag | Props (beyond the CUI options struct's own) | Events |
|---|---|---|
| `:column` `:row` `:box` | `cui_box` options (`:pad :gap :width …`) | |
| `:card` `:scroll` | `cui_card_opts`, `cui_scroll_opts` | |
| `:dialog` `:dialog-footer` | `:title`, `:open` | `:on-close` (Escape, scrim, x) |
| `:text` `:badge` | `:text` | |
| `:heading` | `:text`, `:level` | |
| `:label` `:secondary` | `:label` / `:text` | |
| `:button` | `:label` | `:on-click` |
| `:switch` `:checkbox` | `:label`, `:checked` | `:on-change bool` |
| `:slider` | `:label`, `:value`, `:min`, `:max` | `:on-change float` |
| `:input` | `:value`, `:max-length` (bytes, default 1024) | `:on-change str`, `:on-submit str` |
| `:progress` | `:value` | |
| `:spacer` `:separator` | | |

Other props go into the widget's CUI options struct, using cui-jolt's
conventions: keywords for enums and icons, `:fill`/`[:pct 50]` sizes, colors
as tokens or hex. An unknown option throws an error that lists the valid ones.
Bare strings render as `:text`.

## Escape hatches

- **`(glimmer-cui.core/with-ui f)`** runs `(f ui)` once, inside the next
  frame, for things CUI exposes as actions rather than state: a toast,
  `set-theme`, `scroll-to`, focus. It can be called from any thread.
- **`[:cui/immediate {:render (fn [ui] ...)}]`** is an immediate-mode island.
  `render` runs every frame with the ui handle and a frame arena bound, so any
  `cui.core` call works there: a widget the tag table lacks, a terminal, a
  canvas. Its cost is per frame. A throw is reported and the frame goes on.
- **`:ref`** receives the node when it is created. `(glimmer-cui.core/rect
  node)` then gives its rectangle from the latest frame. Only nodes with a
  `:ref` pay for recording one.

## Threading and the wake patch

While the window loop runs, glimmer schedules re-renders onto it instead of
rendering inline. A `swap!` from an nREPL eval or a worker thread queues its
re-render for the next frame. If the loop is asleep waiting for input,
`cui_app_wake` wakes it. CUI had no cross-thread wake, so this repo carries
`patches/cui-app-wake.patch`. It adds `cui_app_wake(app)` with an
implementation per platform:

- X11: a self-pipe the wait selects on
- Win32: `PostMessage`
- Cocoa: an application-defined event
- GLFW: `glfwPostEmptyEvent`
- SDL2: `SDL_PushEvent`

It's a candidate to send upstream. Only the X11 path has been compiled and
exercised so far.

## Running

```sh
jolt native     # clone CUI a8f71c0, apply the wake patch, build native/libcui
jolt test       # headless suite: bare context, synthetic input, no display
jolt bench      # glimmer-cui vs cui-jolt direct mode, per frame
jolt floor      # the same UI from C
jolt shots      # render the examples to target/*.png with the software renderer
jolt -M:examples -m glimmer-cui.hello      # needs a display
jolt -M:examples -m glimmer-cui.settings   # needs a display
```

`a8f71c0` is the CUI commit cui-jolt's bindings were generated from.

### Testing without a display

`glimmer-cui.headless` drives the same frame code as the window loop on a
bare CUI context (CUI's own embedding path, which needs no window):

```clojure
(def s (h/open {:width 420 :height 300}))
(h/mount! s [app])
(h/step! s 3)
(h/click! s (gc/rect @save-button))   ; move, press, release, one frame each
(h/type-text! s "hello")
(h/screenshot! s "target/app.png")    ; CUI's software rasterizer
(h/close! s)
```

While a session is open the backend counts as running, so re-renders are
scheduled exactly as in the window loop.

## Not done yet

- The tag table is hand-written and covers about 20 of CUI's ~150 components.
  The rest could be generated from cui-jolt's `gen/gen.py` metadata (functions,
  option structs, out-parameter kinds), the way `cui.core` is.
- The window loop (`run-app`) and the wake have not been run here: this box
  has no display. Everything behind `render-frame!` is covered headlessly.
- Array-valued props (charts, tabs, selects) and struct out-parameters (date
  and color pickers) aren't mapped yet.
