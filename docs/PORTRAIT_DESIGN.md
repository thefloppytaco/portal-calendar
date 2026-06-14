# PortalHub — Orientation-Adaptive Board Design

A design reference for laying the board out in **landscape vs portrait**. Consult this
before changing any view's structure. The rule of thumb: **portrait is not a rescale of
landscape — it's a re-layout.**

## Why a rescale isn't enough

The Portal+ panel is 1920×1080. The two orientations have opposite shapes:

| | Landscape (1920×1080) | Portrait (1080×1920) |
|---|---|---|
| Aspect | wide | tall |
| Natural split | sidebar **beside** content | header **above** content |
| Week view | 7 columns × ~250px wide — roomy | 7 columns × ~150px wide — **events truncate to nothing** |

So the week view, in particular, must change its *axis* in portrait. Other views already
suit a tall screen and only need to reflow.

## Principles

1. **Adapt structure, not just size.** Pick the layout axis that matches the aspect.
2. **Same information, rearranged.** Don't drop content in portrait; lay it out differently.
3. **Touch-first at arm's length.** Targets ≥ 44–48px (1dp = 1px at this 160-dpi panel).
4. **One source of truth.** `BoardController.isPortrait()` (the orientation setting, or the
   live screen config in "auto") decides every branch. No scattered orientation checks.
5. **Cheap to rebuild.** Orientation changes rebuild the board (`MainActivity.attachBoard`),
   so layout code runs fresh per orientation — keep `buildUi` branches clean, not toggled
   at runtime.

## The board shell

- **Landscape:** `contentRow` is HORIZONTAL — sidebar (300dp wide) │ main area (weight 1).
- **Portrait:** `contentRow` is VERTICAL — header strip (top, fixed height) ／ main (weight 1).
  - The header strip is the sidebar's content, but **banded**: clock + date on the left,
    weather on the right of the same line; "TODAY" + the next event(s) below; Settings / ✕
    at the right edge. Keep it ≤ ~300dp tall so the calendar gets the vertical room.

## Per-view layout

### Week — the one that really changes
- **Landscape — day COLUMNS** (unchanged): a header row (weekday · number · weather) over
  seven vertical event columns. Roomy; events stack top-to-bottom by time.
- **Portrait — day ROWS** (new): seven day rows stacked vertically; the list scrolls.
  - **Row anatomy:**
    - *Left rail* (fixed ~96dp): weekday in small caps (`WED`), the day number large, weather
      (icon + temp) beneath. **Today**: accent text + a faint tinted row background + an accent
      left rule. Tapping the rail jumps to the **Day** timeline for that date.
    - *Events* (weight 1): the day's events as a vertical stack of slim chips —
      `▏ 12:30 · Chani piano`, the `▏` being the calendar color. Tapping a chip opens the
      event detail (with **Remove**). All-day events first, then timed, in time order.
    - *Empty day*: a faint `—`.
  - **Height:** content-driven, min ~72dp per row; the seven rows scroll within the main area.
    No "+N more" — a busy day just makes its row taller and the week scrolls.

### Month
- **Both orientations:** the 7-column grid. In portrait the cells become tall-and-narrow
  (~154×260px) which reads fine. The weekday header reorders by the week-start setting in
  both. No change needed.

### Day (rolling timeline)
- **Both:** the vertical hour grid (`DayTimeline`) with the now-line and overlap columns.
  Portrait gives it *more* vertical room — strictly better. No change.

### Plan (2-week agenda)
- **Both:** vertical day sections; naturally tall. No change.

## Nav bar
- **Landscape:** title + controls on one row.
- **Portrait:** title on its own line, controls wrap below in a horizontal scroller (already
  done — the weighted title was otherwise starved to a sliver).

## Implementation map

- `isPortrait()` → `buildUi` picks the `contentRow` axis **and** which week structure to build.
- Week: `buildWeekColumns()` (landscape) **or** `buildWeekRows()` (portrait); only one exists
  per board instance.
- `renderWeek()` dispatches to `renderWeekColumns()` / `renderWeekRows()` by orientation.
- Reuse the event-chip look and `showDetails(ev)` tap target across both.

## Future ideas (not built yet)
- Portrait Month could optionally collapse to one tall week at a time if cells feel cramped.
- The portrait header could rotate "today → tomorrow" on a timer to use the band.
