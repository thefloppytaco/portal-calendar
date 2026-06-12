# Family Calendar for Meta Portal

[![Latest release](https://img.shields.io/github/v/release/thefloppytaco/portal-calendar)](https://github.com/thefloppytaco/portal-calendar/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

Turn a discontinued **Meta Portal+ (gen 1)** into an always-on family hub — a wall
display that syncs everyone's Google and Apple calendars, runs chore charts with star
rewards, keeps shared lists and a meal plan, and (optionally) gets AI superpowers.
Think commercial family-display subscription, built from a $30 secondhand Portal,
running entirely on your LAN.

[![Get it on OpenPortal](https://andronedev.github.io/openportal/openportal-badge.svg)](https://andronedev.github.io/openportal/apps/com.portal.calendar)

| Week board | Month board |
|---|---|
| ![Week view](docs/week-view.png) | ![Month view](docs/month-view.png) |

## Everything it does

### 📅 Calendar
- **Four views** — Day, Week, Month, and Plan (a rolling two-week agenda); the
  always-on takeover lands on the current week.
- **Reads any iCal feed**: Google Calendar secret addresses, iCloud public links,
  any `.ics`/`webcal://` URL. Recurring events, edited instances and cancellations
  handled properly. One color per person.
- **Two-way event creation** — add events from the board (`+ Add`) or the setup page;
  they're written into the *real* calendar (iCloud via CalDAV with an app-specific
  password, Google via its API with your own OAuth client), so they appear on
  everyone's phones natively.
- **Weather** — free Open-Meteo forecasts: current conditions in the sidebar, daily
  highs in the week header, an hour-accurate forecast on each event.

### ⭐ Chores & stars
- Chores belong to a family member (or **several at once** — one card each), repeat
  on chosen weekdays or run **one-time** on a date, then retire themselves.
- Kids tap big cards to complete them and earn stars toward a weekly goal, with a
  per-kid tally and progress bar.
- Add chores on the board (`+ Add`, with a quick-pick bank that **learns your
  family's frequent chores**) or on the page; long-press a card to remove one.
- Optional **per-kid PIN**: only that kid — or a parent — can check off their chores.

### 📝 Lists, meals & magic words
- **Shared lists** (groceries, to-dos, packing) editable on the board and from any
  phone on the Wi-Fi; link any list **two-way with Google Tasks** to use it away
  from home.
- **Meal planner** — a week of breakfast/lunch/dinner/snack slots backed by a recipe
  box; tap a planned meal on the board to read its recipe while cooking.
- **Magic words** — a calendar event titled `todo: …`, `groceries: …` or
  `chore: Chani water the plants` is hidden from the board and routed to the right
  place (typo-tolerant; lists auto-create; a member's name assigns the chore on the
  event's date).
- **Inbox calendars** — add a dedicated shared calendar as an *Inbox*: every event in
  it is a command, nothing from it renders, and no prefixes are needed — plain
  phrases like `add oat milk to groceries` or `remind Chani to water the plants`
  just work. Hide the calendar from view on everyone's phones and it clutters nothing.

### ✨ AI (optional, bring your own free Gemini key)
Entirely opt-in: until the Settings toggle is on **and** a key is saved, no AI
options appear anywhere. The key is validated against Google's live model list, the
model picker is populated from it, and calls auto-fall-back if a model is retired.
- **Smart Import** — paste an email or photograph a school flyer; AI proposes events,
  list items and chores, and nothing is added until you confirm.
- **✨ Plan a meal** — name a dish (board or page): AI writes the recipe, slots it
  into the menu, and creates a **dedicated shopping list named after the recipe** —
  check off what's in the pantry, shop the rest.
- **AI recipes** for the recipe box, and **smarter inbox parsing** (free phrasing,
  typo cleanup) with the rule-based parser as automatic fallback.

### 🛡 Family-proofing
- **Kid lock** — an optional 4-digit PIN gates adding, Settings, and deletions on
  the touchscreen, while chore and list check-offs stay kid-friendly.
- **Feature toggles** — hide the Chores, Lists or Meals tabs entirely.
- **Display size** — a live slider (70–160%) zooms the whole UI; drag on your phone
  and watch the board resize (10″ Portals like ~110–125%).
- **Guided setup wizard** — scan a fresh Portal's QR and a skippable wizard walks
  through everything above; re-run it anytime from Settings.

## Requirements

- Meta Portal+ gen 1 (`aloha`, Android 9 / API 28) with **ADB enabled** and the
  [Immortal launcher](https://github.com/starbrightlab/immortal) provisioned.
  Other Portal models are untested — reports welcome.
- A computer with `adb` for the one-time install.
- Wi-Fi shared by the Portal and your phones.

## Install

Grab the APK from [Releases](https://github.com/thefloppytaco/portal-calendar/releases), then:

```sh
adb install -r portal-calendar.apk
adb shell am start -n com.portal.calendar/.MainActivity
```

Wireless adb works too: with the Portal plugged in over USB, run `adb tcpip 5555`,
unplug, then `adb connect <portal-ip>:5555`. Note the TCP mode doesn't survive a
Portal reboot (no root means it can't be persisted) — replug USB and re-run
`adb tcpip 5555` after a restart.

**No `adb`?** You can install straight from your browser with
[OpenPortal](https://andronedev.github.io/openportal/apps/com.portal.calendar) — plug
the Portal into any Chromium-based browser via USB, click install, and skip the
`adb` commands above. No driver or toolchain needed.

That's it. The board shows a QR code; everything else happens from your phone.

> **10″ Portal / text too small?** There's a **Display size** control on the setup
> page (and under ⚙ on the board) that zooms the whole UI from 80% to 140%.

## Setup

### 1. Add the calendars to display (read)

Scan the QR code (or open `http://<portal-ip>:8090` in any browser on the same Wi-Fi)
and paste each person's calendar link with a name and color:


# Recommended: lets the takeover guard reclaim the screen from the stock
# photo frame no matter how it got there (it only ever watches for the frame):
adb shell appops set com.portal.calendar GET_USAGE_STATS allow
```

Wireless adb works too: with USB connected once, `adb tcpip 5555`, unplug, then
`adb connect <portal-ip>:5555` (TCP mode doesn't survive a Portal reboot — replug
and re-run after restarts).

**That's the whole computer part.** The board shows a QR code; scan it and the
**setup wizard** walks you through family members, calendars, two-way sync, weather,
the always-on screen and display size — every step skippable, everything changeable
later. Add the page to your phone's home screen and it opens like an app.

## Building from source

JDK 21, Android SDK (compileSdk 36), Gradle wrapper included:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
ANDROID_HOME=/path/to/android-sdk \
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## How it works (the interesting bits)

The Portal is locked-down Android 9: no Google Play Services, no root, locked
bootloader. The design works within that:

- **Reading calendars** uses plain ICS feeds — the one auth-free universal interface —
  parsed with [biweekly](https://github.com/mangstadt/biweekly), including proper
  `RECURRENCE-ID` override handling.
- **Writing** uses real APIs: CalDAV for iCloud (OkHttp, because `HttpURLConnection`
  refuses the PROPFIND verb) and Google's Calendar/Tasks REST with a bring-your-own
  Desktop OAuth client (the sign-in dead-ends on a localhost URL you paste back —
  or swap `localhost` for the Portal's IP and the Portal finishes it itself).
- **The screensaver takeover** is a small war story. The stock launcher re-asserts
  the system screensaver setting on every resume, dream windows draw *above*
  activities, and the Portal's presence policy sometimes skips the screensaver and
  sleeps directly. So the app listens for dream/screen-off broadcasts, wakes the
  device with an `ACQUIRE_CAUSES_WAKEUP` wakelock, relaunches the board in a timed
  salvo — and, with the optional usage-stats grant, a 30-second guard reclaims the
  screen from the stock photo frame no matter what path it snuck in through.
- **The setup page** is served from the Portal itself (NanoHTTPD on `:8090`) as an
  installable PWA. No cloud, no accounts; nothing leaves your LAN except the feed
  fetches and the APIs you explicitly connect.

## Privacy & security

Calendar URLs, the iCloud app-specific password, Google OAuth tokens, and the
optional Gemini key live only in the app's private storage on the Portal. Treat
secret calendar links like passwords. The setup page is unauthenticated by design —
anyone on your Wi-Fi can open it — so treat your home network accordingly. The
optional usage-stats permission is used solely to detect the stock photo frame.

## License

[MIT](LICENSE)
