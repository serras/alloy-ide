# Design

## Context

The panel in `viewer/AlloyInstancePanel.kt` embeds Alloy's `VizGraphPanel` with a single `VizState` held by `AlloySolutionModel`. Stepping calls `vizState.loadInstance(instance(state))` and `VizGraphPanel.remakeAll`. The Text and Table tabs are `JBTextArea`s showing `A4Solution.toString(state)` and `format(state)`.

The Alloy 6.2.0 renderer (`alloy4graph`, `alloy4viz`) fixes colors in many places that the plugin cannot reach through the theme:

| where | color |
|---|---|
| `GraphNode` outline | `Color.BLACK` |
| `GraphNode` label | `BLACK`, or `WHITE` only when the fill is exactly black, red or blue |
| `GraphNode` selection | `Color.LIGHT_GRAY` |
| `GraphEdge` default color | `Color.BLACK` |
| `GraphEdge` highlight | `Color.LIGHT_GRAY` |
| `GraphViewer`, `VizGraphPanel` backgrounds | `Color.WHITE` |

`GraphViewer`s are created inside `VizState.getGraph(parent, projection)` → `StaticGraphMaker.produceGraph`. That method is public and caches one panel per projection. `VizGraphPanel` can show several states, but only side by side: their container is a local of its constructor. It also builds its own atom combos (`TypePanel`) for projected signatures.

`A4Solution.toString(state)` prints one line per signature, then per field of that signature, then per skolem, always in that order. `A4Solution.eval(expr, state)` returns the `A4TupleSet` behind each line.

## Goals / Non-Goals

**Goals:**
- Recolor every part of the Alloy-drawn graph under a dark theme without forking or shadowing Alloy classes.
- Keep the color conversion and the tuple diff as pure functions that can be unit-tested headlessly.
- Keep the existing behavior of single-state results, the projection chooser, solution enumeration and the zoom popup.

**Non-Goals:**
- No dark version of the visualizer's export (*Save as* PNG/PDF from the graph popup). It keeps the light colors.
- No customization UI for the dark palette, and no per-user override of the converted colors.
- No graph diff highlighting in the Visualization tab. The diff is only in the Text tab.
- No showing of more than two states at once.

## Decisions

### 1. Recolor at paint time with a `JLayer` and a color-mapping `Graphics2D`

Each graph is wrapped in a `JLayer` whose `LayerUI.paint` passes the view a delegating `Graphics2D`. The delegate rewrites every `setColor`/`setPaint`/`setBackground` (and `create()` returns another mapping delegate), so that every hard-coded color goes through one mapping, as do the magic ones. The mapping applies only while the IDE theme is dark (`!JBColor.isBright()`). Under a light theme the layer paints straight through.

The role of each color is decided by the color itself, because that is all the delegate sees:
- **Background:** `Color.WHITE`, when used as a background or fill of the canvas, maps to the IDE panel background (`UIUtil.getPanelBackground()`).
- **Lines:** `Color.BLACK` maps to the IDE foreground, slightly dimmed for outlines. Edges and outlines both use `BLACK`, so both become foreground. Labels drawn with `BLACK` or `WHITE` become the label color, light.
- **Selection and highlight:** `LIGHT_GRAY` maps to a lighter surface (the converter's "elevation" idea).
- **Fills and edges:** any other color uses the fill rule when it is used for a `fill*` call and the edge rule when it is used for `draw*` and `drawString`. The delegate records the current color and decides at the drawing call, not at `setColor`.

Text drawn by `drawString`/`drawGlyphVector` is always drawn in the light label color when the current color is black or white. This is what makes labels light under rule (B). Colored edge labels go through the edge rule like their edge.

*Alternatives:*
- Rewriting `GraphNode`/`GraphEdge` colors after `getGraph` misses the hard-coded outlines, labels and backgrounds.
- Copying `alloy4graph` classes into the plugin under the same package depends on classloader order.
- Rendering to an image and transforming pixels loses the graph's interaction (dragging nodes, selection, popup).

### 2. A pure `DarkColors` converter in HSL

This is a small Kotlin object with `fill(c)`, `edge(c)` and the role constants, implementing the PaletaColor Pro role rules in HSL with the hue kept:
- **fill** ("muted dark surface"): S' = 0.85 S. The lightness is mapped into a dark band, roughly L' = 0.20 + 0.15 · L, so that every fill sits in [0.20, 0.35]. Lighter originals stay lighter within the band. This keeps grays (lightgray, white fills) distinct from colored types, and distinct from the canvas.
- **edge** (the converter's "primary" rule): L' = L + 0.30 if L < 0.35; L − 0.10 if L > 0.65; else L + 0.12. S' = 0.85 S if S > 0.8.
- **label**: the IDE foreground. **canvas**: the IDE background.

These are plain functions on `java.awt.Color`, so they can be unit-tested without a UI. The exact band constants can be tuned once seen in `runIde`, and the tests assert properties rather than exact values:
- the hue is preserved;
- fill L' ≤ 0.35;
- a fill contrasts with the label by at least 4.5:1;
- an edge contrasts with the canvas by at least 3:1.

### 3. Repaint on theme change

The panel subscribes to `LafManagerListener.TOPIC` on the application bus with its disposable, and repaints the graphs when the theme changes. The colors are mapped at paint time, so nothing needs to be regenerated or relaid out.

### 4. Own trace panes instead of `VizGraphPanel`

The Visualization tab becomes the plugin's own component:
- An `AlloyStatePanes` container with one or two captioned panes. Its layout puts them in a column when `height > width`, and in a row otherwise. The layout is recomputed in `doLayout`, so resizing switches it.
- For each pane, a `JBScrollPane` with the `JLayer`-wrapped `vizState.getGraph(frame, projection)`.
- A strip below with one atom selector (`ComboBox`) per projected signature. This replaces `VizGraphPanel`'s `TypePanel`s, and builds the one `AlloyProjection` used for both panes.

The Table tab uses the same container with two `JBTextArea`s, one per state.

*Alternative:* keep `VizGraphPanel` with two states and change the layout of its hidden container through the component tree. This was rejected: it depends on the internal structure of a third-party class, and it would still leave its white `TypePanel`s.

The zoom popup is `GraphViewer`'s own (it shows `pop` on right-click), so it keeps working inside the plugin's pane.

### 5. Two `VizState`s per trace in `AlloySolutionModel`

`loadInstance` changes a `VizState` in place, so each pane needs its own:
- The first one is today's `vizState`, with the magic layout or the theme it was copied from.
- The second one, `nextVizState`, is `VizState(vizState)` loaded with the successor's instance.

`setProjected` and `clearProjections` apply to both. `goTo` loads both:
- the selected state into the first;
- `successor(state)` into the second, where `successor(s) = if (s + 1 < traceLength) s + 1 else loopState`.

Single-state results have no second `VizState`. New-solution theme copying keeps using the first one.

*Alternative:* a single `VizState` reloaded for each pane while it paints. This was rejected: `getGraph` caches per projection, and reloading would rebuild both graphs at every repaint.

### 6. Tuple-level diff from `A4Solution.eval`

A pure `TraceDiff` builds, for states *a* and *b*, a list of lines. There is one line per signature and field (in `getAllReachableSigs()` order, each followed by its fields, as `toString` prints them) and per skolem (`getAllSkolems()`). Each line has a label and three ordered sets of tuples: kept, added and removed, as strings, using `A4TupleSet` iteration order. The lines and their labels come from the same sources the analyzer prints. This keeps the order and naming identical to the single-state text, and avoids parsing the printed text.

The Text tab renders this list in a read-only `JTextPane` with a styled document:
- added tuples use `DiffColors`' inserted attributes (`EditorColorsManager.globalScheme` attributes for `DiffColors.DIFF_INSERTED`);
- removed tuples use the deleted attributes, struck through;
- unchanged lines use the context-help foreground.

These attributes come from the active editor color scheme, so they follow the theme without the dark converter.

*Alternative:* IntelliJ's diff viewer over the two printed texts. This was rejected in favor of Alloy-aware marks: a tuple-level diff never reports reordering as a change.

## Risks / Trade-offs

- [Some JBR rendering paths bypass `setColor`, e.g. `drawImage` of cached glyphs or `setPaint` with a `GradientPaint`] → Alloy's renderer only uses `setColor`, `fill`, `draw` and `drawString`. The delegate forwards `setPaint(Color)` through the mapping and leaves other paints untouched. A test paints a graph into a `BufferedImage` under a dark mapping and checks that no white canvas pixels remain.
- [HiDPI and transforms] → The delegate only wraps; transforms, clips and hints go to the real `Graphics2D`.
- [Black used both for edges and outlines] → Both become foreground-like. That is acceptable: dark edges must be light on a dark canvas anyway.
- [A user theme color equal to pure white or black is treated as canvas or line] → This matches the light rendering, where white fills already look like the canvas and are only told apart by their outline, which stays visible.
- [Two graphs double the layout cost per step] → `getGraph` caches per projection, and only the panes whose state changed are rebuilt. In practice the second pane becomes the first after a step and can be reused.
- [Dropping `VizGraphPanel` loses its atom combos' behavior] → The new selectors reproduce it: they default to the first atom, keep the choice while the atom still exists, and are listed in projection order. `AlloyProjectionTest` covers this.

## Migration Plan

Nothing to migrate. No settings or stored state change. The selected-tab preference keeps working.
