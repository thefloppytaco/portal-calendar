# Reddit post #3 — v3.3 (PortalHub: voice, dark mode, day timeline, browser install)

**Title:**
Update: my $30-Portal family hub now takes voice commands, has dark mode + a real day-view timeline, and installs from your browser (v3.3, renamed PortalHub)

**Body:**

Few days ago I posted my Portal family board — the "going down a $300 subscription
smart-display's feature list and building it all onto a $30 Portal" project. I, uh, kept
building (a lot of Claude in the loop). It's outgrown the name, so it's **PortalHub** now.
Here's what's new since the last post:

🎤 **Voice commands** — honestly the one I'm most hyped about. Tap the mic and say
*"add dentist Tuesday at 3"* or *"put eggs on the groceries list"* and it just does it. It runs
on **your own** free Gemini key, so it's a private, no-subscription version of the Alexa thing —
your audio only ever hits Google on your own key, not some middleman. Tap-to-talk, not
always-listening (the Portal won't give sideloaded apps a wake-word).

📅 **A real day timeline** — the day view is now an hour-by-hour grid with a live "now" line,
and **overlapping events sit side by side in columns** so you can actually see when things clash,
like Google Calendar's day view. (Before it just stacked start-time tiles and you couldn't tell.)

🌙 **Dark mode** — light / dark / **auto** (goes dark at night) / **match-system**. The whole
board, not a tint.

🔄 **Mount it however you want** — landscape, portrait, or **auto-rotate**: in auto it follows
the accelerometer and flips when you physically turn the Portal. (The Portal flat-out refuses to
auto-rotate sideloaded apps, so I ended up reading the accelerometer myself and locking the
orientation to match.) And portrait isn't a squished landscape — the week turns into a
**day-per-row agenda** so it actually fits a tall screen.

📆 **Start your week on Monday** — or Sunday, or Saturday. Plus pick which view the board opens
on (day / week / month / 2-week plan). Small thing, people asked.

🗑️ **Delete events from the board** — tap an event → Remove and it deletes from the *real*
calendar too (iCloud/Google), so it's gone off everyone's phones. Two-way sync finally goes both
directions.

📦 **Backup & clone** — export your whole setup (calendars, members, chores, the lot) as one code
and paste it into a second Portal. Handy if you've got one in the kitchen and one in the office.

🌐 **No `adb`?** — you can now install straight from a Chromium browser thanks to the **OpenPortal**
project (plug the Portal in over USB, click install). `adb` still works the old way too.

**Already installed?** `adb install -r` upgrades in place and keeps all your data.

Same deal as always: **gen-1 Portal+ is the only model I've tested** (let me know if it runs on a
Go / Mini / gen-2), everything stays on your LAN, MIT licensed. This whole thing has basically
been built off this subreddit's requests — so **please drop bugs in the comments, and tell me
what you want next.** I'll keep going.

Still standing on the shoulders of the **starbrightlab/Immortal** folks — none of this works
without that project.

👉 https://github.com/thefloppytaco/portal-calendar
