# Reddit post #2 — v2.9 (full family hub: chores, meals, magic words, AI)

**Title:**
Update: the Portal family calendar is now a full family hub — chores with star rewards, meal
planning, magic words, and optional AI (v2.9)

**Body:**

Posted my family calendar app yesterday and then basically didn't stop (Claude Fable Max from)
building. You know those $300 smart family displays with the monthly subscription? I've been
going down their feature list and building it all onto a $30 Portal. Seventeen releases later,
here's where it's at:

⭐ Chores & stars — each kid gets a column of big tappable chore cards and earns stars toward a
weekly goal. Repeating or one-time chores, add them right on the touchscreen (there's a quick-pick
bank that learns what your family adds a lot), assign one chore to both kids at once, and you can
give each kid a PIN so they can't check off their sibling's stuff.

📝 Lists & meals — shared grocery/to-do lists you can edit from the board or any phone in the
house (lists can also sync two-way with Google Tasks for when you're out). Plus a weekly meal
planner with a recipe box — tap tonight's dinner on the board to see the recipe while you cook.

🪄 Magic words — honestly my favorite part. Make a calendar event called groceries: oat milk from
anywhere and it never shows on the calendar — it just lands on the grocery list. Set up a hidden
"inbox" calendar and you don't even need the prefix: remind Ozzie to pack his swim bag just works.
Typos are fine, lists create themselves.

✨ AI (optional) — bring your own free Gemini key and you can photograph a school flyer and it
pulls out the events/chores/list items for you to confirm. Or name a dish and it writes the
recipe, puts it on the menu, and makes a shopping list just for that recipe so you can check off
what's already in the pantry. No key = none of this even shows up, and everything else works fine
without it.

Other stuff: Day + agenda views, weather, a parent PIN for the touchscreen, a live display-size
slider (10″ Portal people: try 110–125%), toggles to hide tabs you don't use, and a proper setup
wizard — scan the QR on a fresh install and it walks you through everything.

Already installed? adb install -r upgrades in place. Also run this one-liner — it fixes the stock
photo frame occasionally stealing the screen back: adb shell appops set com.portal.calendar
GET_USAGE_STATS allow

Same deal as before: gen-1 Portal+ tested (let me know if it runs on a Go/Mini/gen-2), everything
stays on your LAN, MIT licensed.
