# Moving from XCSoar to XCSoar Mobile

XCSoar Mobile runs XCSoar's own flight computer under a new touch
interface. Glide computations, task rules, contests, airspace warnings,
the values in the tiles, units, profiles and data files are XCSoar's.
What changes is how you reach them.

Both apps install side by side (XCSoar Mobile is `org.xcsoar.mobile`),
so you can keep XCSoar while you try it.

## Bring your data

The app keeps its files in its own `XCSoarData` folder:
`Android/data/org.xcsoar.mobile/files/XCSoarData`, visible from a computer
over USB. The folders inside are XCSoar's (`maps`, `airspace`,
`waypoints`, `tasks`, `profiles`, `logs`).

| What | How |
|---|---|
| Your settings | Menu → Profiles → Import a .prf file…, and pick your profile. The app reads XCSoar's profile keys, so your units, safety heights, airspace filters and so on come along. |
| Map, airspace, waypoints, RASP | Menu → Data files: download them from XCSoar's repository, or pick a file on the phone. |
| Planes | Menu → Aircraft & crew, or copy your `.xcp` files into `XCSoarData/profiles/planes`. A plane can use a built-in polar, three points of your own, or a WinPilot `.plr` file (Import a polar file (.plr)). |
| Tasks | Copy `.tsk` (or SeeYou `.cup`) files into `XCSoarData/tasks`, then Menu → Task → Load…. WeGlide tasks are listed there too. |

## Where things are

| In XCSoar | In XCSoar Mobile |
|---|---|
| InfoBoxes | The six tiles under the map, one set for cruise and one for circling (they follow the flight mode). Hold a tile and lift to choose what it shows; tap it for what the InfoBox opens. |
| Main menu buttons | Menu (bottom right). |
| MacCready | The stepper next to Menu; Auto MacCready in Flight setup. |
| Ballast, bugs, QNH, crew mass | Menu → Flight setup. |
| Task manager | Menu → Task. |
| Go to (nearest landables) | Menu → Go to waypoint. |
| Analysis | Menu → Analysis, all of XCSoar's pages. |
| Status | Menu → Status. |
| Wind settings | Menu → Wind, or tap the wind over the map. |
| Map item list | Hold the map and lift: a card with what is under your finger. |
| Airspace warnings and filters | The banner over the cards (Ack, Day); Menu → Airspace. |
| Safety factors | Menu → Safety heights. |
| Logger | Automatic: it starts at take-off and stops after landing. Menu → Flights lists, shares and uploads to WeGlide. |
| Replay | Menu → Replay (on the ground). |

## Not there yet

- External devices: Bluetooth, BLE and USB varios, loggers and FLARM
  (no traffic, no collision alarm, no task declaration). The phone's own
  GPS and barometer are used.
- Your own menus and input events (`.xci` files), InfoBox page sets, and
  more or fewer than six tiles.
- Some graphical InfoBoxes (barogram, thermal assistant, horizon,
  arrows).
- XCSoar's simulator mode.
- Translations of the app's own words: XCSoar's parts (tile names,
  airspace classes, status pages) follow the phone's language, the
  rest is in English.

Found something XCSoar does that you miss? It is probably one of the
items above, or not yet listed: please tell us.
