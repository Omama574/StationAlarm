
Your output would be verified with Codex

# StationAlarm — CLAUDE.md

Android app that alerts users approaching a railway station or saved place.
Works at train speeds (100–300 km/h) via layered geofences + adaptive polling.

---

## Before You Write Anything

<important if="you are about to answer a question about code">
NEVER answer questions about specific files or behaviour without reading the
relevant file first. No speculation. If you haven't opened it, open it.
</important>

<important if="you are about to delete or remove code">
Before deleting ANYTHING — a function, import, field, or file — grep the entire
codebase for usages. If you cannot confirm zero usages, DO NOT delete it. State
what you found and ask.
</important>

<important if="you are unsure">
Stop. Name what's unclear and ask rather than guessing. Surface tradeoffs.
Present multiple interpretations when ambiguity exists.
</important>

---

## Behaviour-Locked Files — DO NOT CHANGE SEMANTICS

These files are frozen. Pure structural refactors (formatting, renaming) are OK.
Any semantic/behavioural change requires explicit approval before touching:

- `service/LocationService.kt`
- `service/AlarmAudioController.kt`
- `service/ServiceWakeLocks.kt`
- `service/ServiceNotifications.kt`
- `geofence/GeofenceManager.kt`
- `receiver/GeofenceBroadcastReceiver.kt`

---

## Tech Stack

| Layer | Tech |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + AndroidView (osmdroid) |
| Map | osmdroid 6.1.20 + OSM MAPNIK tiles |
| Geocoding primary | LocationIQ via Cloudflare Worker proxy |
| Geocoding fallback | Photon by Komoot — called DIRECTLY from device (not via Worker) |
| Database | Room SQLite v3 |
| Backend config | Firebase Remote Config (`geocoding_backend_url`) + Crashlytics |

---

## Architecture

### Search (3-tier)
1. **StationData** — offline Indian railway JSON, instant, zero API calls
2. **LocationIQ** — via Cloudflare Worker, 500ms debounce
3. **Photon** — direct from device (preserves per-user IP quota, NOT pooled)

### Alarm Flow
```
Station selected → StationConfigBottomSheet → StationRepository.addActiveStation()
→ GeofenceManager: 6 geofences per station (Outer/Mid/Inner × Entry/Exit)
→ LocationService: adaptive GPS polling
→ GeofenceBroadcastReceiver → AlarmActivity
→ dismissStation() → removeGeofencesForStation()
```

---

## Key Files

| File | Role |
|---|---|
| `service/LocationService.kt` | Core alarm engine — LOCKED |
| `geofence/GeofenceManager.kt` | Geofence setup/teardown — LOCKED |
| `receiver/GeofenceBroadcastReceiver.kt` | Geofence event handler |
| `repository/StationRepository.kt` | Only place that touches Room + GeofenceManager |
| `ui/screens/MapSearchScreen.kt` | Map tab — osmdroid + search + radius |
| `ui/viewmodel/MapSearchViewModel.kt` | Geocoding calls, search logic |
| `ui/screens/HomeScreen.kt` | My Stations tab — active alarms list |
| `ui/screens/StationConfigBottomSheet.kt` | Shared alarm config (both tabs) |
| `ui/screens/AlarmActivity.kt` | Full-screen alarm UI |
| `network/LocationIqService.kt` | Retrofit interface — LocationIQ (via Worker) |
| `network/PhotonService.kt` | Retrofit interface — Photon (direct) |
| `network/GeocodingModels.kt` | All geocoding models + toSearchResult() mappers |
| `network/RetrofitClient.kt` | OkHttp client, dynamic Worker URL |
| `data/StationData.kt` | Loader for stations.json |
| `data/db/StationDatabase.kt` | Room v3 — saved_places + active_stations |
| `StationAlarmApplication.kt` | App init — osmdroid config, Firebase, Remote Config |

---

## Non-Negotiable Rules

1. **No API keys in APK** — all geocoding goes via Cloudflare Worker
2. **No `countrycodes` restriction** — app is global
3. **Photon is always called directly from device** — never via Worker
4. **Room DB schema changes require a migration** — bump version in `StationDatabase.kt`
5. **No Mapbox** — TOS violation (geocoding results on non-Mapbox maps)
6. **Geocoding stack is locked** — LocationIQ primary, Photon fallback, no other sources

---

## Database Schema (Room v3)

**`saved_places`** — `id, name, lat, lon, radiusKm, notify, vibrate, sound, notes, createdAt`  
**`active_stations`** — active alarm state per station

---

## Build Commands

```bash
./gradlew compileDebugKotlin   # check compile only
./gradlew assembleDebug        # full debug build
```

---

## When Compacting

Preserve: locked file list, non-negotiable rules, current Room DB version, any
files modified this session, and any decisions made about geocoding stack.
