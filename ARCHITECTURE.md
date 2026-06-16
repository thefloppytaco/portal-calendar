# PortalHub — Architecture

> **Read this first.** PortalHub is a **single, self-contained Android application**
> that runs entirely on a Meta Portal device. There is **no cloud backend, no
> server fleet, no managed database, and no microservices.** The "backend" is an
> HTTP server *embedded in the app itself* so a phone can configure it over the
> local Wi-Fi. Where the requested template assumes web/cloud constructs
> (RDS, Kubernetes, Stripe, a CI server, etc.), this document says so plainly
> instead of inventing them — the honest shape of the system matters more than
> filling a box.

---

## 1. PROJECT STRUCTURE

Single Gradle module (`:app`). All application code is one Kotlin package,
`com.portal.calendar`, organized here by architectural role (the files are flat
on disk; the grouping below is conceptual).

```
portal-calendar/
├── build.gradle.kts                 # root Gradle config
├── settings.gradle.kts              # module list (":app"), repositories
├── gradle.properties
├── local.properties                 # SDK path (git-ignored)
├── gradlew / gradlew.bat            # Gradle wrapper
├── gradle/wrapper/                  # wrapper jar + properties
├── README.md
├── ARCHITECTURE.md                  # (this file)
├── LICENSE                          # MIT
├── docs/                            # screenshots + design notes
│   ├── PORTRAIT_DESIGN.md
│   ├── announcements/               # archived Reddit/release posts
│   └── *.png                        # week/month/day/portrait/dark screenshots
└── app/
    ├── build.gradle.kts             # app module: SDK levels, version, deps
    └── src/main/
        ├── AndroidManifest.xml      # permissions, activity/service/receiver
        ├── res/
        │   ├── drawable/ic_launcher.xml
        │   └── values/strings.xml
        ├── assets/
        │   └── config.html          # the entire phone-facing config UI + wizard (one file)
        └── java/com/portal/calendar/
            │
            ├─ ── App / host lifecycle ──
            │   App.kt               # Application: process-wide singletons; idle-takeover dream logic
            │   MainActivity.kt      # fullscreen host activity for the board; orientation/idle policy
            │   KeepAliveService.kt  # foreground service keeping the process alive for takeover
            │   BootReceiver.kt      # relaunch on BOOT_COMPLETED / MY_PACKAGE_REPLACED
            │
            ├─ ── Presentation (the on-device "board") ──
            │   BoardController.kt   # the whole calendar/board UI + refresh loops (largest file)
            │   DayTimeline.kt       # rolling day timeline with overlapping-event columns
            │   ChoresTab.kt         # chores board tab (per-member cards, stars)
            │   RoutinesTab.kt       # routines board tab (kids' checklists, opt-in)
            │   ListsTab.kt          # shared lists board tab
            │   MealsTab.kt          # meal-plan board tab
            │   Celebration.kt       # confetti/star particle overlay (CelebrationView)
            │   FocusHighlight.kt    # D-pad/remote focus outline overlay (Portal TV)
            │   Palette.kt           # theme colors (light/dark), corner radii
            │
            ├─ ── Phone-facing config "backend" ──
            │   ConfigServer.kt      # NanoHTTPD HTTP server on :8090; all /api/* routes
            │   ConfigStore.kt       # typed accessors over the "config" SharedPreferences
            │   ConfigBundle.kt      # whole-device export/import (backup & clone)
            │
            ├─ ── Domain / local data model ──
            │   Data.kt              # atomic JSON-file persistence (tmp+fsync+rename) w/ global lock
            │   Members.kt           # family members (name, color, optional PIN)
            │   Chores.kt            # chores + star tally + weekly goals + learned suggestions
            │   Routines.kt          # kids' get-ready checklists (sections, no stars)
            │   FamilyLists.kt       # shared lists (groceries/to-dos/…)
            │   Meals.kt             # weekly meal plan + recipe box
            │   DeletedEvents.kt     # tombstones so deletes stay hidden until feeds catch up
            │
            ├─ ── Calendar read/write + parsing ──
            │   SyncManager.kt       # fetches & caches ICS feeds; expands recurrence; builds instances
            │   CalDav.kt            # iCloud CalDAV (OkHttp PROPFIND/PUT)
            │   GoogleCal.kt         # Google Calendar API + OAuth (device-pasted desktop client)
            │   GoogleTasks.kt       # Google Tasks two-way list sync
            │   Writers.kt           # routes event writes to the chosen account (iCloud/Google)
            │   MagicWords.kt        # parses "groceries: …" / "chore: …" command events; fuzzy match
            │
            ├─ ── External integrations ──
            │   Weather.kt           # Open-Meteo forecast + geocoding (no key)
            │   Gemini.kt            # optional Google Gemini: smart import, recipes, NL parsing
            │   VoiceInput.kt        # tap-to-talk audio capture for voice commands
            │
            ├─ ── Multi-device ──
            │   FamilySync.kt        # hub-and-spoke LAN sync over mDNS + the config server
            │
            └─ ── Idle behavior ──
                Screensaver.kt       # idle mode (takeover / yield / off) + yield timing
```

**No `src/test` or `src/androidTest` directory exists** — see §8.

---

## 2. HIGH-LEVEL SYSTEM DIAGRAM

There is no traditional Users → Frontend → Backend → Database chain. Everything
in the dashed box runs **on one Portal device**; the "frontend" the family
configures from is a phone browser talking to an HTTP server *inside the app*.

```
   ┌──────────────┐        ┌──────────────┐        ┌──────────────┐
   │  Family on    │        │  Family on    │        │  Other Portals │
   │  the Portal   │        │  their phones │        │  (hub/spoke)   │
   │  (touch /     │        │  (config PWA) │        │                │
   │   remote)     │        │               │        │                │
   └──────┬───────┘        └──────┬───────┘        └──────┬───────┘
          │ taps / D-pad           │ HTTP :8090            │ HTTP :8090 + mDNS
          ▼                        ▼                       ▼
  ┌───────────────────────────────────────────────────────────────────┐
  │  PORTAL DEVICE  —  PortalHub app process (com.portal.calendar)      │
  │                                                                     │
  │   MainActivity ── BoardController (the on-device board UI)          │
  │        │                                                            │
  │   App (singletons) ── ConfigServer (NanoHTTPD :8090, the "backend") │
  │        │                     │                                      │
  │   SyncManager / Writers   Domain objects (Chores/Routines/Lists/…)  │
  │        │                     │                                      │
  │        ▼                     ▼                                      │
  │   ┌────────────────────────────────────────────┐                   │
  │   │  LOCAL STORE: JSON files (filesDir) +        │                   │
  │   │  SharedPreferences("config")                 │  ← §4            │
  │   └────────────────────────────────────────────┘                   │
  └───────┬───────────────┬───────────────┬───────────────┬───────────┘
          │ HTTPS          │ HTTPS         │ HTTPS         │ HTTPS
          ▼                ▼               ▼               ▼
   ICS/webcal feeds   Google APIs     Apple iCloud     Open-Meteo / Gemini
   (read calendars)   (Cal + Tasks)   (CalDAV write)   (weather / AI)        ← §5
```

C4 Level 1, in words: **one Person actor** (a household) interacts with **one
Software System** (the PortalHub app on a Portal) through two surfaces — the
always-on board (touch/remote) and a phone config page. That system reads
external **iCal feeds** and writes to **Google/iCloud** calendars, optionally
calls **Open-Meteo** and **Gemini**, and synchronizes peer Portals over the LAN.
No system owned by this project runs off-device.

---

## 3. CORE COMPONENTS

There are no separately deployed services. "Components" here are in-process
modules within the single APK.

### Frontend A — The Board (on-device UI)
- **Purpose:** the always-on family display: calendar (Day/Week/Month/Plan),
  Chores, Lists, Meals, and optional Routines tabs; settings overlay; voice mic.
- **Tech:** Kotlin, **programmatic Android Views** (no XML layouts, no Compose),
  custom drawing (`GradientDrawable`, `CelebrationView`, `FocusHighlight`).
  `BoardController` builds and refreshes the whole tree; `MainActivity` is a thin
  fullscreen host.
- **Deployment:** ships inside the APK; launched as the `LAUNCHER` activity and
  re-asserted by the idle-takeover logic.

### Frontend B — The Config Page / Setup Wizard (phone)
- **Purpose:** configure calendars, members, chores/routines/lists/meals,
  weather, theme/layout, sync, AI, backup — from any phone on the Wi-Fi. Served
  as an installable PWA.
- **Tech:** a single static **`assets/config.html`** (vanilla HTML/CSS/JS, no
  framework, no build step) that calls the in-app `/api/*` JSON endpoints.
- **Deployment:** served by the embedded server; "installed" via the browser's
  Add-to-Home-Screen.

### Backend — Embedded Config Server
- **Purpose:** the only "backend." Serves `config.html`, a PWA manifest/icon, and
  ~40 JSON `/api/*` routes (config, members, chores, routines, lists, meals,
  weather, AI, layout, scale, features, sync, backup/import, calendar connect).
- **Tech:** **NanoHTTPD 2.3.1** on **port 8090**, single class `ConfigServer`,
  request bodies read as UTF-8 with a size cap.
- **Deployment:** started in `App.onCreate()`; lives in the app process, kept
  alive by `KeepAliveService`.

### Supporting in-process modules
- **SyncManager** — periodically fetches configured iCal feeds, parses them
  (biweekly), expands recurrence, applies tombstones, and publishes event
  instances to the board.
- **Writers / CalDav / GoogleCal / GoogleTasks** — two-way calendar/task writes.
- **FamilySync** — hub-and-spoke LAN replication (see §4 / §5).
- **Domain objects** (`Chores`, `Routines`, `FamilyLists`, `Meals`, `Members`) —
  pure data + JSON persistence via `Data`, each exposing `statusJson`/`mutate`.

---

## 4. DATA STORES

**No external database, cache, or message queue.** All state is local to the
device. There are two stores:

### A. JSON document files — `Data.kt`, in the app's private `filesDir`
Atomic writes (temp file → `fsync` → rename), a process-global lock around every
read-modify-write, and corrupt files quarantined to `*.corrupt` rather than
silently wiped. Conceptually a tiny embedded document store.

| File | Holds | Key fields |
|---|---|---|
| `members.json` | Family members | `id, name, color, pin` |
| `chores.json` | Chore definitions | `id, title, icon, memberId, days[]\|oneTime+date` |
| `chore_done.json` | Chore completion log (append-only) | `choreId, date, memberId` |
| `star_goals.json` | Per-member weekly star goal | `memberId, goal` |
| `chore_history.json` | Learned chore suggestions | `title, icon` |
| `routines.json` | Routine checklist items | `id, title, icon, memberId, section, days[]\|oneTime` |
| `routine_done.json` | Routine completion log | `itemId, date` |
| `lists.json` | Shared lists + items | `id, name, items[], gtasksId?` |
| `mealplan.json` | Weekly meal slots | `date, slot, text` |
| `recipes.json` | Recipe box | `title, ingredients[], steps` |
| `deleted_events.json` | Delete tombstones | event keys (hide until feeds catch up) |
| `magic_done.json` | Per-device command-event dedup | processed UIDs (stays local, never synced) |
| `weather_cache.json` | Last Open-Meteo response | cached forecast (re-derivable) |

### B. `SharedPreferences("config")` — `ConfigStore.kt` + others
Key/value settings **and credentials**. Notable keys: `feeds` (calendar list),
`feature_*` (tab toggles), `theme`/`orientation`/`week_start`/`default_view`/
`ui_scale`, `kid_pin`, `chore_effects`, `idle_mode`/`idle_yield_min`,
`sync_role`/`sync_hub_manual`/`sync_mirror_all`, `wizard_done`, and the secrets:
`g_client_id`/`g_client_secret`/`g_refresh`/`g_access`/`g_cals`/`g_email`
(Google), `icloud_email`/`icloud_password`/`icloud_cals`, `ai_key`/`ai_model`,
`wx_*` (weather location).

### Replication, not a database
`FamilySync` makes a **hub** Portal authoritative; **spokes** poll the hub's
`/api/family` for the shared JSON files and write edits back. This is LAN file
replication over HTTP — there is no shared/managed DB and no message queue.

---

## 5. EXTERNAL INTEGRATIONS

All over HTTPS. The only required-at-runtime integrations are the calendar feeds;
everything else is optional and user-supplied.

| Service | Purpose | Integration method | Auth |
|---|---|---|---|
| **iCal / webcal feeds** | Read everyone's calendars (Google secret iCal, iCloud public, any `.ics`) | `SyncManager` HTTP GET + **biweekly** parse (RRULE/EXDATE) | none (secret URL) |
| **Google Calendar API** (`calendar/v3`) | Two-way event create/delete | REST via `GoogleCal` | OAuth2 (user's own Desktop client; `g_refresh` token) |
| **Google OAuth2** (`accounts.google.com`, `oauth2.googleapis.com`) | Obtain/refresh Google tokens | code → token exchange; localhost/Portal-IP redirect | OAuth2 |
| **Google Tasks API** (`tasks/v1`) | Two-way sync of a shared list ↔ a Tasks list | REST via `GoogleTasks` | OAuth2 (`auth/tasks`) |
| **Apple iCloud** (`caldav.icloud.com`) | Write events to iCloud calendars | **CalDAV** PROPFIND/PUT via **OkHttp** | app-specific password |
| **Open-Meteo** (`api.open-meteo.com`, `geocoding-api.open-meteo.com`) | Weather forecast + city/ZIP lookup | REST via `Weather` | none (keyless) |
| **Google Gemini** (`generativelanguage.googleapis.com/v1beta`) | Optional: smart import, recipes, meal planning, voice/NL parsing | REST via `Gemini` | user's API key (`ai_key`) |
| **mDNS / NSD** (LAN, not internet) | Hub discovery for multi-Portal sync (`_portalhub._tcp`) | `android.net.nsd` + multicast lock | none (trusted LAN) |

**Distribution-time only (not called by the app):** GitHub Releases hosts the
APKs; the **OpenPortal** community catalog pulls them for no-`adb` installs. There
is currently **no in-app updater** (a candidate feature — see §9).

> Not used: Stripe, SendGrid, Firebase, analytics SDKs, or any paid/tracking
> third party. The app ships with **no Google Play Services dependency** (the
> Portal has none).

---

## 6. DEPLOYMENT & INFRASTRUCTURE

**No cloud provider. No EC2/Lambda/S3/RDS/Kubernetes.** The deployment target is
a physical, discontinued consumer device on the user's home network.

- **Target hardware:** Meta **Portal+ gen-1** (`aloha`, Android 9 / API 28) with
  **ADB enabled** and the **Immortal** launcher provisioned. Other Portal models
  (incl. Portal TV) are community-reported.
- **Build artifact:** a single **APK** (`app-debug.apk`), `minSdk`/`targetSdk` 28,
  `compileSdk` 36, Java 11 source/target, `isMinifyEnabled = false` (no R8).
- **Signing:** releases ship the **debug-signed** APK (no release keystore).
  Signer cert SHA-256 `4a149e84…` — must stay constant so installs update in
  place. Verify with `apksigner verify --print-certs` before publishing.
- **Distribution channels:**
  1. **OpenPortal** catalog (Chromium browser → USB → install; no `adb`).
  2. **`adb install -r`** of the latest release APK.
  3. Direct GitHub Release download.
- **"CI/CD":** **manual, local.** Build with the Gradle wrapper, verify the
  signer cert, then `gh release create` with two assets (a stable
  `portal-calendar.apk` + a versioned `portal-calendar-vX.Y.apk`). There is **no
  hosted CI** (no GitHub Actions/Jenkins) and **no automated pipeline**.
- **On-device runtime services:** the embedded NanoHTTPD server (`:8090`), a
  foreground `KeepAliveService`, a `BootReceiver` (relaunch on boot / app
  replace), and mDNS advertise/discover for sync.
- **Monitoring:** none (no APM/crash reporting/telemetry by design). Diagnosis is
  via `adb logcat` during development.

---

## 7. SECURITY CONSIDERATIONS

- **Auth model — external services:** OAuth2 for Google (user's own Desktop
  client + refresh token), an Apple **app-specific password** for iCloud CalDAV,
  a user-supplied **API key** for Gemini. Calendar feed URLs are bearer-secret
  (treated like passwords).
- **Auth model — the config server:** **unauthenticated by design.** Anyone on
  the same Wi-Fi can open `http://<portal-ip>:8090`. The threat model assumes a
  trusted home LAN; this is documented for the user, not enforced.
- **Authorization on the device:** an optional 4-digit **kid-lock PIN** gates
  adding events, opening Settings, and deletions on the touchscreen; optional
  **per-member PINs** gate checking off that member's chores/routines. PINs live
  only on-device and are **never sent over HTTP** (`Members.publicJson` strips
  them; only the board's in-process PIN pad sees them).
- **Data at rest:** all secrets and data live in the app's **private internal
  storage** (`filesDir` + `SharedPreferences`), not world-readable on a
  non-rooted device. No additional encryption-at-rest layer.
- **Data in transit:** **TLS** for every external API and feed fetch.
  `usesCleartextTraffic="true"` is enabled to allow (a) the local `:8090` config
  server and (b) plain-`http://` iCal feeds users may paste; it is not used for
  any credentialed external call.
- **Backup/clone bundle:** `ConfigBundle` export contains **all credentials**
  (Google refresh token, iCloud password, Gemini key) base64-encoded — the UI
  warns to treat the code like a password.
- **Permissions requested:** `INTERNET`, `ACCESS_NETWORK_STATE`,
  `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (mDNS), `WAKE_LOCK`
  (takeover), `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `RECORD_AUDIO`
  (optional voice), and optional adb-granted `PACKAGE_USAGE_STATS` (takeover
  guard). No location, contacts, or storage permissions.
- **Known sharp edges:** (a) `/api/family` serves `members.json` (incl. PINs)
  raw over the LAN to spokes — fine for a trusted network, but unauthenticated;
  (b) `sync_mirror_all` shares **one** Google refresh token across devices, which
  can trigger `invalid_grant` rotation conflicts (the default non-mirror spoke
  does **not** share credentials and is safe).
- **No third-party security tooling** (SAST/dependency scanning/secret scanning)
  is wired up.

---

## 8. DEVELOPMENT & TESTING

### Local setup
```sh
# Requires JDK 21 and an Android SDK (compileSdk 36).
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
ANDROID_HOME=/path/to/android-sdk \
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Install + launch on a Portal with ADB enabled:
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.portal.calendar/.MainActivity
# Optional (takeover guard): adb shell appops set com.portal.calendar GET_USAGE_STATS allow
```
`local.properties` holds the SDK path and is git-ignored. Wireless ADB
(`adb tcpip 5555` → `adb connect <ip>:5555`) works but does not survive a Portal
reboot.

### Testing
- **No automated test suite** (no JUnit/Espresso/`src/test` or `src/androidTest`).
- **Verification is manual, on real hardware:** build → install → drive the board
  and config page → confirm via screenshots and `adb logcat`. (E.g. the
  v3.5.0 launch crash was caught only by an on-device launch, not by the clean
  compile — device-testing is the de-facto gate.)
- For the config page, the JS is sanity-checked with `node --check` on the
  extracted `<script>` block.

### Code quality tools
- **None configured** — no ESLint/ktlint/detekt/SonarQube, no pre-commit hooks.
  The Kotlin compiler + Android Lint (via Gradle) are the only static checks.
  Style is maintained by convention (match surrounding code).

---

## 9. FUTURE CONSIDERATIONS

**Known technical debt**
- **Release signing uses the debug keystore.** Functional (consistent cert) but
  not best practice; a proper release keystore would be a one-way migration that
  breaks in-place updates, so it's deliberately deferred.
- **No automated tests / CI.** All regressions are caught by hand on-device.
- **`BoardController.kt` is very large** (~3k lines) — the board UI, overlays,
  composers, and refresh loops could be split.
- **Unauthenticated config server** and **PIN-over-LAN to spokes** (§7).
- **Mirror-all shares one Google refresh token** → `invalid_grant` risk (§7).

**Planned / candidate features**
- **In-app updater** — discussed: check the GitHub `releases/latest` API, show an
  "update available" banner, and (device permitting) a download-and-install path
  via `PackageInstaller` + `REQUEST_INSTALL_PACKAGES`. Notifier is low-risk; the
  one-tap install hinges on the Portal exposing a usable package installer and
  may need a one-time `appops` grant — needs on-device verification.
- Continued community/forum feature requests (the backlog model the project runs
  on), e.g. recent additions: Routines, Portal-TV focus outline, chore
  celebrations, idle-yield, multi-Portal sync.

**Possible behavioral refinement**
- Takeover idle mode currently suppresses the system screensaver device-wide even
  after the board is exited (clarified in copy as of v3.5.1); a future option
  could suspend takeover on explicit exit.

---

## 10. GLOSSARY

| Term | Meaning |
|---|---|
| **PortalHub** | This app (package `com.portal.calendar`). Formerly "Family Calendar." |
| **Portal / Portal+** | Meta's discontinued smart-display devices; **Portal+ gen-1** (`aloha`, API 28) is the primary target. **Portal TV** is the remote-controlled model. |
| **aloha** | Meta's codename for the Portal+ gen-1 hardware. |
| **Immortal** | Third-party launcher provisioned on the Portal; its photo-frame screensaver is what the takeover logic contends with. |
| **OpenPortal** | Community app catalog that installs APKs to a Portal over USB without `adb`. |
| **The board** | The always-on, on-device UI (`BoardController` in `MainActivity`). |
| **Config page / wizard** | The phone-facing setup UI (`assets/config.html`) served by the embedded server. |
| **Hub / Spoke** | Multi-Portal sync roles: the **hub** is the source of truth; **spokes** poll it and write through to it. |
| **Takeover / Yield / Off** | Idle modes: keep the board up forever / let the screensaver run after N min / hold screen only while open. |
| **Magic words** | Calendar events titled e.g. `groceries: …` or `chore: …` that are routed to a list/chore instead of being shown. |
| **Inbox calendar** | A dedicated feed whose every event is treated as a command, with no prefix needed. |
| **CalDAV** | The protocol used to write to iCloud calendars (PROPFIND/PUT). |
| **ICS / webcal** | iCalendar feed format / its `webcal://` URL scheme; the read path for all calendars. |
| **RRULE / EXDATE / RECURRENCE-ID** | iCalendar recurrence rule / excluded dates / per-instance override; expanded by biweekly. |
| **mDNS / NSD** | Multicast DNS service discovery (`android.net.nsd`) used to find the sync hub on the LAN. |
| **Dream** | Android's term for a screensaver (`ACTION_DREAMING_STARTED/STOPPED`). |
| **ADB** | Android Debug Bridge; used for install and one-time permission grants. |
| **GMS** | Google Mobile Services / Play Services — **absent** on the Portal, so unused. |
| **PWA** | Progressive Web App; how the config page is "installed" on a phone. |
| **Stars / Goal** | Chore reward points and the per-member weekly target (chores only; routines have no stars). |
| **Section** | A routine item's time-of-day grouping: morning / afternoon / evening / anytime. |

---

## 11. PROJECT IDENTIFICATION

| Field | Value |
|---|---|
| **Project name** | PortalHub (app id `com.portal.calendar`; Gradle root name "Family Calendar") |
| **Version** | 3.5.1 (`versionCode` 26) |
| **Repository** | https://github.com/thefloppytaco/portal-calendar |
| **Releases** | https://github.com/thefloppytaco/portal-calendar/releases |
| **Install (no ADB)** | https://andronedev.github.io/openportal/apps/com.portal.calendar |
| **License** | MIT |
| **Maintainer** | GitHub `@thefloppytaco` (solo/community project) |
| **Platform** | Android 9 (API 28) on Meta Portal hardware |
| **Last updated** | 2026-06-16 |
