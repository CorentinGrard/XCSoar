# Moving from XCSoar to Buteo

Buteo runs XCSoar's own flight computer under a new touch
interface. Glide computations, task rules, contests, airspace warnings,
the values in the tiles, units, profiles and data files are XCSoar's.
What changes is how you reach them.

Buteo is not made by the XCSoar project: please report its problems at
https://github.com/CorentinGrard/XCSoar/issues, not to XCSoar.

Both apps install side by side (Buteo is `app.corentin.buteo`), so
you can keep XCSoar while you try it.

## Bring your data

The app keeps its files in its own `XCSoarData` folder:
`Android/data/app.corentin.buteo/files/XCSoarData`, visible from a
computer over USB. The folders inside are XCSoar's (`maps`, `airspace`,
`waypoints`, `tasks`, `profiles`, `logs`).

| What | How |
|---|---|
| Your settings | Menu → Profiles → Import a .prf file…, and pick your profile. The app reads XCSoar's profile keys, so your units, safety heights, airspace filters and so on come along. |
| Map, airspace, waypoints, RASP | The first start offers the map, airspace and airfields for where you are. Later, Menu → Data files: download them from XCSoar's repository, or pick a file on the phone. |
| Planes | Menu → Aircraft & crew, or copy your `.xcp` files into `XCSoarData/profiles/planes`. A plane can use a built-in polar, three points of your own, or a WinPilot `.plr` file (Import a polar file (.plr)). |
| Tasks | Copy `.tsk` (or SeeYou `.cup`) files into `XCSoarData/tasks`, then Menu → Task → Load…. WeGlide tasks are listed there too. |

## Where things are

| In XCSoar | In Buteo |
|---|---|
| InfoBoxes | The six tiles under the map, one set for cruise and one for circling (they follow the flight mode). Hold a tile and lift to choose what it shows; tap it for what the InfoBox opens. |
| Main menu buttons | Menu: the button left of the target, at the top. |
| MacCready | The MacCready tile: tap it for − / +; Auto MacCready in Flight setup. |
| Ballast, bugs, QNH, crew mass | Menu → Flight setup. |
| Task manager | Menu → Task. |
| Go to (nearest landables) | Menu → Go to waypoint. |
| Analysis | Menu → Analysis, all of XCSoar's pages. |
| Status | Menu → Status. |
| Wind settings | Menu → Wind, or tap the wind over the map. |
| Map item list | Hold the map and lift: a card with what is under your finger. |
| Airspace warnings and filters | The banner over the cards (Ack, Day); Menu → Airspace. |
| Safety factors | Menu → Safety heights (Startup Task MC is "MacCready at start" there). |
| Logger | Automatic: it starts at take-off and stops after landing. After the landing, a summary of the flight shares it and uploads it to WeGlide; Menu → Flights has all of them. |
| Thermal assistant | While circling, on the glider over the map (Menu → Map → Thermal assistant), and as a tile. |
| Circling zoom | Menu → Map → Zoom of its own. |
| Sounds of events | Menu → Voice callouts: airspace, final glide, task points and climbs, spoken. |
| Replay | Menu → Replay (on the ground). |

## Not there yet

- External devices: Bluetooth, BLE and USB varios, loggers and FLARM
  (no traffic, no collision alarm, no task declaration). The phone's own
  GPS and barometer are used.
- Your own menus and input events (`.xci` files), InfoBox page sets, and
  more or fewer than six tiles.
- The horizon InfoBox (the phone has no attitude data); the other
  graphical InfoBoxes are there.
- XCSoar's simulator mode.
- Translations of the app's own words: XCSoar's parts (tile names,
  airspace classes, status pages) follow the phone's language, the
  rest is in English.

Found something XCSoar does that you miss? It is probably one of the
items above, or not yet listed: please tell us.
