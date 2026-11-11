# aus-roads copy specification — C-G4

**Tone:** calm, concrete, local-first, and reassuring. Australian English. Sentence case. Explain the offline/fallback behaviour without blaming the user. Buttons use a verb plus their object wherever context alone would be ambiguous.

**Status:** `Built` matches the current working tree; `Required` is the exact replacement/addition needed to close a documented gap. Dynamic `{values}` are substitutions, not placeholder copy. Error text lives in `edges.md` and is not duplicated here.

## App shell and map

| Key | Context | Text (verbatim) | Tone note | Char budget | Status |
|---|---|---|---|---:|---|
| app.name | Launcher/app identity | “aus-roads” | product name stays lowercase | 16 | Built |
| nav.map | Bottom navigation | “Map” | concrete destination | 12 | Built |
| nav.nearby | Bottom navigation | “Nearby” | familiar destination | 12 | Built |
| nav.pins | Bottom navigation | “Pins” | familiar destination | 12 | Built |
| nav.settings | Bottom navigation | “Settings” | familiar destination | 12 | Built |
| map.loading | Map readiness overlay | “Loading offline map…” | names local work | 30 | Built |
| map.demo-coverage | Banner over bundled pack | “Demo map · Adelaide area only” | honest scope boundary | 34 | Built |
| map.attribution | Map lower edge | “© OpenStreetMap contributors” | legally precise | 32 | Built |
| map.canvas-a11y | Native map semantics | “Interactive map. Hold or press Enter to drop a pin; drag or use arrow keys to pan; pinch or use plus and minus to zoom.” | teaches equivalent touch and keyboard paths | 140 / 3 lines | Built |
| map.hold-hint | First-use teaching chip | “Hold anywhere on the map to drop a pin” | one action, plain language | 48 | Built |
| map.history | Route-history button | “History” | short, conventional | 12 | Built |
| map.my-location | Location FAB semantics | “My location” | familiar map convention | 20 | Built |
| map.locating | Map location spinner semantics/snackbar | “Locating…” | short active state attached to persistent progress | 16 | Built |
| map.result-banner | Selected result chip | “{name} · {category}” | selection + disambiguator | 48 / 1 line | Built |
| map.result-details-a11y | Result banner action | “Open details for {name}” | named action | 64 | Built |
| map.no-pack.title | Missing-pack state | “No map pack installed” | says what is absent | 30 | Built |
| map.no-pack.hint | Missing-pack guidance | “Open Settings to download the South Australia map pack.” | tells where and what | 72 / 2 lines | Built |
| map.manage-pack | Map recovery button | “Manage map pack” | verb + object | 24 | Built |
| traffic.count | Map traffic status | “{count} events” | concise local status | 20 | Built |
| traffic.retry | Failed traffic refresh action | “Retry” | repeats the traffic fetch while prior snapshots remain | 12 | Built |
| traffic.open-settings | Invalid-credential recovery action | “Open traffic settings” | verb + explicit destination | 24 | Built |

## Search and place details

| Key | Context | Text (verbatim) | Tone note | Char budget | Status |
|---|---|---|---|---:|---|
| search.placeholder | Empty search field | “Search places, roads, POIs…” | scopes searchable things | 36 | Built |
| search.a11y | Leading icon semantics | “Search” | conventional | 12 | Built |
| search.clear-a11y | Trailing action semantics | “Clear search” | names object | 20 | Built |
| search.searching | Result-card loading row | “Searching…” | brief active state | 18 | Built |
| search.retry | Failed-query action | “Retry” | repeats the unchanged query | 12 | Built |
| search.manage-pack | Unavailable-index action | “Manage map pack” | names recovery destination | 24 | Built |
| search.no-results | Query returned zero hits | “No results for “{query}”” | echoes exact query | 64 / 2 lines | Built |
| search.group.places | Result heading | “Places” | friendly, no raw schema | 24 | Built |
| search.group.roads | Result heading | “Roads” | friendly, no raw schema | 24 | Built |
| search.group.poi | Result heading | “Points of interest” | spells out POI | 28 | Built |
| search.group.water | Result heading | “Water features” | friendly | 24 | Built |
| search.group.parks | Result heading | “Parks” | friendly | 24 | Built |
| search.group.other | Result heading | “Other” | safe human fallback | 24 | Built |
| search.kind.place | Result subtitle/detail | “Place” | singular type | 24 | Built |
| search.kind.road | Result subtitle/detail | “Road” | singular type | 24 | Built |
| search.kind.poi | Result subtitle/detail | “Point of interest” | friendly, not raw `poi` | 24 | Built |
| search.kind.water | Result subtitle/detail | “Water feature” | friendly | 24 | Built |
| search.kind.park | Result subtitle/detail | “Park” | friendly | 24 | Built |
| search.kind.other | Result subtitle/detail | “Other” | safe fallback | 24 | Built |
| feature.save | Details-sheet secondary action | “Save pin” | verb + object | 18 | Built |
| feature.directions | Details/pin primary action | “Directions” | established map convention | 18 | Built |

## Location and Nearby

| Key | Context | Text (verbatim) | Tone note | Char budget | Status |
|---|---|---|---|---:|---|
| nearby.title | Top app bar | “Nearby” | destination | 20 | Built |
| nearby.find | Category heading | “Find nearby” | action-oriented | 24 | Built |
| nearby.using-device | Location status | “Using your device location” | explicit provenance | 40 | Built |
| nearby.waiting | Location status with temporary fallback | “Waiting for a device location fix · showing Adelaide CBD for now” | states wait + safe fallback | 80 / 2 lines | Built |
| nearby.fallback | No-permission location status | “Using Adelaide CBD until you share your location” | honest default | 64 / 2 lines | Built |
| nearby.use-location | Coordinate-card action | “Use my location” | verb + object | 24 | Built |
| nearby.refresh-location | Coordinate-card action | “Refresh my location” | verb + object | 28 | Built |
| nearby.open-location-settings | Blocked-permission action | “Open location settings” | explicit destination | 30 | Built |
| nearby.reference | Coordinate-card title | “This location” | points to shared reference | 24 | Built |
| nearby.coordinate.decimal | Coordinate label | “Decimal” | standard notation | 12 | Built |
| nearby.coordinate.dms | Coordinate label | “DMS” | standard notation | 8 | Built |
| nearby.coordinate.mgrs | Coordinate label | “MGRS” | standard notation | 8 | Built |
| nearby.share | Coordinate-card action/chooser title | “Share location” | verb + object | 24 | Built |
| nearby.share-named-a11y | Emergency row action | “Share {name} location” | names target | 64 | Built |
| nearby.results | Result-list heading | “Results” | concise | 16 | Built |
| nearby.view-named-a11y | Result row map action | “View {name} on map” | names destination and effect | 64 | Built |
| nearby.emergency | Emergency-card heading | “Emergency” | calm, direct | 20 | Built |
| nearby.nearest-hospital | Emergency label | “Nearest hospital” | relationship + object | 24 | Built |
| nearby.nearest-police | Emergency label | “Nearest police” | relationship + object | 24 | Built |
| nearby.emergency-loading | Emergency lookup in progress | “Finding nearby emergency services…” | names the work | 44 | Built |
| nearby.emergency-preparing | Emergency repository preparation | “Emergency details will appear when the map data is ready.” | distinguishes preparation from a completed empty lookup | 72 / 2 lines | Built |
| nearby.emergency-empty | True-empty emergency state | “No emergency services were found in this map pack.” | distinguishes a pack result from a location failure | 64 / 2 lines | Built |
| nearby.share-clipboard-label | Clipboard item label after chooser failure | “Shared location” | names the preserved clipboard object | 24 | Built |
| nearby.try-adelaide | Empty-state fallback action | “Try Adelaide CBD” | verb + explicit fallback | 24 | Built |
| nearby.distance-metres | Result distance | “{distance} m” | local unit | 12 | Built |
| nearby.distance-kilometres | Result distance | “{distance} km” | local unit | 12 | Built |
| nearby.category.fuel | Filter chip | “Fuel” | common language | 20 | Built |
| nearby.category.ev | Filter chip | “EV charging” | common language | 20 | Built |
| nearby.category.hospital | Filter chip | “Hospital” | common language | 20 | Built |
| nearby.category.pharmacy | Filter chip | “Pharmacy” | common language | 20 | Built |
| nearby.category.police | Filter chip | “Police” | common language | 20 | Built |
| nearby.category.fire | Filter chip | “Fire station” | common language | 20 | Built |
| nearby.category.toilets | Filter chip | “Toilets” | Australian wording | 20 | Built |
| nearby.category.water | Filter chip | “Drinking water” | common language | 20 | Built |
| nearby.category.camping | Filter chip | “Camping” | common language | 20 | Built |
| nearby.category.supermarket | Filter chip | “Supermarket” | common language | 20 | Built |

## Pins

| Key | Context | Text (verbatim) | Tone note | Char budget | Status |
|---|---|---|---|---:|---|
| pins.title | List/empty-state heading | “Saved pins” | object + state | 24 | Built |
| pins.empty | First-run list guidance | “No pins yet. Hold the map to drop one, or add coordinates here.” | teaches both creation paths; replaces “Long-press” jargon | 72 / 2 lines | Built |
| pins.loading | Initial list read | “Loading saved pins…” | distinguishes pending read from true empty | 28 | Built |
| pins.retry-load | Failed initial-read action | “Retry saved pins” | verb + exact object | 24 | Built |
| pin.add | FAB/dialog title and primary action | “Add pin” | verb + object | 18 | Built |
| pin.edit | Editor title | “Edit pin” | verb + object | 18 | Built |
| pin.name | Form label | “Name” | plain field label | 20 | Built |
| pin.latitude | Form label | “Latitude” | standard term | 20 | Built |
| pin.longitude | Form label | “Longitude” | standard term | 20 | Built |
| pin.colour | Colour-group label | “Colour” | Australian English | 20 | Built |
| pin.colour-selected-a11y | Selected colour semantics | “Colour: {colour}” | announces state and value | 32 | Built |
| pin.colour.forest | Radio choice semantics | “Forest green” | colour not hex | 20 | Built |
| pin.colour.teal | Radio choice semantics | “Teal” | colour not hex | 20 | Built |
| pin.colour.brown | Radio choice semantics | “Brown” | colour not hex | 20 | Built |
| pin.colour.magenta | Radio choice semantics | “Magenta” | colour not hex | 20 | Built |
| pin.colour.slate | Radio choice semantics | “Slate” | colour not hex | 20 | Built |
| pin.colour.indigo | Radio choice semantics | “Indigo” | colour not hex | 20 | Built |
| pin.drop-title | Long-press sheet title | “Drop a pin here” | confirms gesture | 24 | Built |
| pin.fallback-name | Reverse-geocode fallback | “Pin ({latitude}, {longitude})” | useful identity | 40 | Built |
| pin.save | Drop-pin primary action | “Save pin” | verb + object | 18 | Built |
| pin.save-changes | Edit-sheet primary action | “Save pin changes” | explicit effect | 24 | Built |
| pin.discard-title | Changed-draft confirmation title | “Discard unsaved pin changes?” | names the at-risk object | 40 | Built |
| pin.discard-message | Changed-draft confirmation body | “Your changes haven’t been saved.” | states the consequence plainly | 40 | Built |
| pin.discard | Changed-draft destructive action | “Discard” | explicit destructive verb | 12 | Built |
| pin.keep-editing | Changed-draft safe action | “Keep editing” | preserves the draft and returns control | 18 | Built |
| pin.delete | Pin/detail and confirmation action | “Delete” | concise within a named pin surface/confirmation | 12 | Built |
| pin.delete-a11y | List-card delete semantics | “Delete pin {name}” | names destructive target outside the confirmation | 64 | Built |
| pin.delete-confirm-title | Destructive confirmation title | “Delete “{name}”?” | names the affected pin before persistence | 72 | Built |
| pin.delete-confirm-message | Destructive confirmation body | “This removes the saved pin from this device.” | states scope and consequence | 64 | Built |
| pin.try-again | Failed list-delete action | “Try again” | retries the already-confirmed deletion | 16 | Built |
| pin.edit-a11y | Card action | “Edit pin {name}” | named target | 64 | Built |
| pin.saved | Success snackbar | “Saved “{name}”” | confirms object | 64 | Built |
| pin.deleted | Delete snackbar | “Deleted “{name}”” | confirms object | 64 | Built on map-sheet path |
| action.undo | Snackbar action | “Undo” | conventional reversible action | 12 | Built |
| action.retry | Search/Nearby recovery action | “Retry” | repeats the failed local operation | 12 | Built |
| action.cancel | Dialog/sheet action | “Cancel” | unambiguous modal exit | 12 | Built |

## Routes and navigation

| Key | Context | Text (verbatim) | Tone note | Char budget | Status |
|---|---|---|---|---:|---|
| route.computing | Map progress pill | “Routing…” | names work | 20 | Built |
| route.summary | Result-sheet heading | “{distance} km · {duration} min” | scannable summary | 28 | Built |
| route.directions | Maneuver heading/action | “Directions” | conventional | 18 | Built |
| route.options | Route-sheet heading | “Route options” | concrete | 24 | Built |
| route.avoid-tolls | Labelled switch | “Avoid tolls” | direct | 24 | Built |
| route.avoid-unsealed | Labelled switch | “Avoid unsealed roads” | locally relevant | 28 | Built |
| route.avoid-ferries | Labelled switch | “Avoid ferries” | direct | 24 | Built |
| route.waypoint-field | Optional field | “Add a waypoint” | verb + object | 24 | Built |
| route.waypoint-action-a11y | Waypoint button | “Add waypoint” | verb + object | 24 | Built |
| route.start | Primary route action | “Start navigation” | sentence case + object | 24 | Built |
| route.enable-precise | Permission action | “Enable precise location” | action + permission | 30 | Built |
| route.open-settings | Permission action | “Open location settings” | explicit destination | 30 | Built |
| route.close | Sheet exit | “Close route” | names what closes | 18 | Built |
| route.history-title | History-sheet heading | “Recent routes” | object + recency | 24 | Built |
| route.history-empty | Empty state | “No recent routes” | explicit, not blank | 24 | Built |
| route.history-loading | Initial read | “Loading recent routes…” | distinguishes pending read from true empty | 32 | Built |
| route.history-from | Entry origin | “From: {origin}” | compact metadata | 48 | Built |
| route.history-select-a11y | History entry action | “Select route to {destination}” | named action | 72 | Built |
| route.history-delete-a11y | History-row delete action | “Delete route to {destination}” | names the destructive target | 72 | Built |
| route.history-delete-confirm-title | Destructive confirmation title | “Delete route to “{destination}”?” | names the affected route before persistence | 80 | Built |
| route.history-delete-confirm-message | Destructive confirmation body | “This removes the route from recent routes on this device.” | states local scope and consequence | 72 | Built |
| nav.stop | Active guidance action | “Stop navigation” | sentence case + object | 24 | Built |
| nav.progress | Maneuver progress | “{current} of {total}” | concise orientation | 16 | Built |
| nav.continue | Maneuver instruction | “Continue” | conventional | 16 | Built |
| nav.end | Recovery-state route exit | “End navigation” | verb + object | 20 | Built |
| nav.arrived | Arrival state | “You have arrived” | calm confirmation | 24 | Built |
| nav.done | Arrival action | “Done” | conventional terminal action | 12 | Built |
| nav.battery | Guidance warning | “Battery low — consider stopping navigation” | recommendation, not alarmism | 52 | Built |
| nav.speed-limit | Guidance label | “Speed limit” | familiar | 20 | Built |
| nav.recalculating | Off-route state | “Off route — recalculating…” | what + current action | 36 | Built |
| nav.retry-route | Failed off-route recovery action | “Retry route” | verb + object | 16 | Built |
| nav.retry-location | Location recovery action | “Retry location” | verb + object | 20 | Built |

## Settings and map pack

| Key | Context | Text (verbatim) | Tone note | Char budget | Status |
|---|---|---|---|---:|---|
| settings.title | Screen heading | “Settings” | familiar | 20 | Built |
| settings.theme | Section heading | “Theme” | concise | 16 | Built |
| settings.theme-system | Radio choice | “System default” | familiar | 24 | Built |
| settings.theme-light | Radio choice | “Light” | familiar | 16 | Built |
| settings.theme-dark | Radio choice | “Dark” | familiar | 16 | Built |
| settings.traffic | Section heading | “Live traffic overlay” | names feature | 28 | Built |
| settings.traffic-hint | Labelled switch copy | “Show roadworks, incidents and closures on the map” | explains effect | 64 / 2 lines | Built |
| settings.tts | Section heading | “Text-to-speech alerts” | names feature | 28 | Built |
| settings.tts-hint | Labelled switch copy | “Read navigation instructions aloud” | explains effect | 44 | Built |
| settings.api-keys | Section heading | “Traffic API keys” | names data | 24 | Built |
| settings.api-hint | Guidance | “Optional. Required for NSW and VIC live traffic data. Free registration at the government open data portals.” | requirement + source | 120 / 3 lines | Built |
| settings.nsw-key | Field label | “NSW API key” | named provider | 20 | Built |
| settings.nsw-placeholder | Empty field | “From opendata.transport.nsw.gov.au” | tells source | 44 | Built |
| settings.vic-key | Field label | “VIC API key” | named provider | 20 | Built |
| settings.vic-placeholder | Empty field | “From opendata.transport.vic.gov.au” | tells source | 44 | Built |
| settings.attribution | Section heading | “Attribution overlay” | names feature | 24 | Built |
| settings.retrying | Exact-setting retry in progress | “Retrying…” | brief progress attached to disabled retry action | 16 | Built |
| action.dismiss | Settings-write recovery exit | “Dismiss” | ends the retained retry opportunity | 12 | Built |
| settings.map-pack | Section heading | “Map pack” | names object | 20 | Built |
| map-pack.installed | Pack status | “Installed” | concise success | 16 | Built |
| map-pack.none | Pack status | “Not installed” | concise empty state | 20 | Built |
| map-pack.download | Primary action | “Download map pack” | verb + object | 28 | Built |
| map-pack.redownload | Repair/update action | “Re-download map” | verb + object | 24 | Built |
| map-pack.version | Installed metadata | “Version: {version} · {size}” | scannable | 40 | Built |
| map-pack.checking | Manifest phase | “Checking for updates…” | names work | 28 | Built |
| map-pack.downloading | Transfer phase | “Downloading… {downloaded} / {total}” | progress in object terms | 48 | Built |
| map-pack.verifying | Integrity phase | “Verifying…” | names work | 20 | Built |
| map-pack.extracting | Install phase | “Extracting…” | names work | 20 | Built |
| map-pack.installing | Install phase | “Installing…” | names work | 20 | Built |
| settings.about | Section heading | “About” | familiar | 16 | Built |
| settings.attribution-action | About action | “View full attribution” | verb + object | 28 | Built |

## About and legal discovery

| Key | Context | Text (verbatim) | Tone note | Char budget | Status |
|---|---|---|---|---:|---|
| about.title | Top app bar | “About aus-roads” | product-specific | 24 | Built |
| about.back-a11y | Navigation icon | “Back” | conventional | 12 | Built |
| about.section.app | Section heading | “App” | concise | 16 | Built |
| about.section.data | Section heading | “Map data” | clear ownership | 20 | Built |
| about.section.traffic | Section heading | “Live traffic (planned)” | honest roadmap state | 32 | Built |
| about.section.libraries | Section heading | “Libraries” | clear | 20 | Built |
| about.section.privacy | Section heading | “Privacy” | clear | 20 | Built |
| about.build | Installed build row | “Installed build: {version} · {variant}” | support-ready identity | 64 | Built |
| about.privacy-summary | Settings/About summary | “This app makes no network requests after the first-time map pack download. No analytics. No ads. No accounts. No background location.” | concrete promises | 150 / 4 lines | Built |
| about.osm-attribution | Attribution switch/About | “© OpenStreetMap contributors. Map data is licensed under the Open Database License (ODbL).” | legally precise | 120 / 3 lines | Built |

The full immutable licence/provider/library paragraphs remain verbatim in `android/app/src/main/res/values/strings.xml`; they are legal inventory rather than interaction copy. Changes to those strings require licence/source review, not tone editing.

## Dynamic presentation rules

- Unknown search kinds/classes are humanised by replacing `_`/`-` with spaces and sentence-casing; raw values such as `amenity=fuel` never reach the user.
- Search duplicates retain four-decimal coordinates in the subtitle so identically named stores remain distinguishable.
- Nearby distances use whole metres under 1 km and one decimal kilometre at/above 1 km.
- Coordinates use Australian-facing southern/eastern notation in search and Decimal/DMS/MGRS in Nearby; share text uses six-decimal machine-readable latitude/longitude plus an OpenStreetMap URL.
- User-supplied names truncate visually to one line where space is constrained; accessibility names retain the complete value.
