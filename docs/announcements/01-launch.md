# Reddit post #1 — launch (the always-on family calendar)

If you're running Immortal on a gen-1 Portal+, here's a new use for it: I built an always-on
family calendar board, and it's now my favorite thing the Portal has ever done.

Features:
- Week + month views, live clock, "Today" agenda sidebar, one color per family member
- Pulls from any iCal feed — Google Calendar secret addresses, iCloud public links, whatever.
  Recurring events, edited instances, cancellations all handled
- Two-way sync: a + Add button right on the touchscreen (and on a setup page the Portal serves
  to your phone) — events get written into the actual calendar over CalDAV (iCloud) or the
  Calendar API (Google), so they pop up on everyone's phones
- It replaces the screensaver. When the Portal idles, the calendar takes the screen and holds it
  24/7 instead of the photo frame. Toggle on/off from the setup page
- Zero cloud: the Portal hosts its own config page on your LAN (http://<portal-ip>:8090, QR shown
  on screen). Setup is all from your phone — feeds cached on disk, survives reboots untethered

Notes for this crowd: you need ADB on and Immortal provisioned. It plays nice with Immortal —
exiting the board drops you back to the launcher, and I deliberately didn't fight Immortal's
SettingsGuard for screensaver_components. Instead it catches DREAMING_STARTED/SCREEN_OFF
broadcasts and wakes the screen out from under the dream — because dream windows draw above
activities on this build, and the presence policy skips the dream entirely when it thinks the
room's empty. There's a 1–2 sec flash of the photo frame during the hand-off; the proper fix
would be a one-line guard in Immortal's DreamPolicy (PR for that someday). Full write-up of the
power-policy weirdness is in the README.

Gen-1 Portal+ is the only model I've tested. If you run it on a Go/Mini/gen-2, report back.

MIT, APK in releases: https://github.com/thefloppytaco/portal-calendar

Huge credit to the starbrightlab/Immortal folks — none of this is possible without that project.
