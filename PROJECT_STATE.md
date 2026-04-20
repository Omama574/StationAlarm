# StationAlarm — Project State

> **Last updated:** 2026-04-18
> Update this file at the end of every session with what changed and what's next.

---

## What This App Does

Android app that alerts the user when approaching a railway station or any custom-saved place. Designed to work reliably at high train speeds (100–300 km/h) by defeating Android doze/batching with layered geofences and adaptive polling.

---

## Tech Stack

| Layer | Tech |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + AndroidView (for osmdroid) |
| Map | osmdroid 6.1.20 + OpenStreetMap (MAPNIK tiles) |
| Geocoding primary | LocationIQ via Cloudflare Worker proxy |
| Geocoding fallback | Photon by Komoot (direct from device) |
| Database | Room SQLite v3 |
| Backend config | Firebase Remote Config (backend URL), Crashlytics |
| Preferences | DataStore Preferences (theme, distance unit, alarm sound URI) |
| Core alarm engine | LocationService + GeofenceManager (foreground service) |

---

## Architecture

### Search Flow (3-tier)
```
User types in MapSearchScreen
    │
    ├─ Tier 1: StationData (offline JSON) — Indian railway stations
    │          Instant, zero API calls. Returns if any match found.
    │
    ├─ Tier 2: LocationIQ via Cloudflare Worker
    │          Primary live geocoding. 500ms debounce.
    │          Worker URL controlled by Firebase Remote Config.
    │
    └─ Tier 3: Photon by Komoot (direct from device)
               Fallback. Called DIRECTLY (not via Worker) to keep
               per-user IP quota instead of pooled server quota.
```

### Alarm Flow
```
Station selected → StationConfigBottomSheet → radius/sound/vibrate config
    → StationRepository.addActiveStation()  [withTransaction; rolls back on geofence-reg failure]
    → GeofenceManager.setupGeofencesForStation()
        → 6 geofences per station (Outer/Mid/Inner × Entry/Exit)
    → LocationService (foreground) polls GPS at adaptive intervals
    → GeofenceBroadcastReceiver fires (gated on isArmed) → AlarmActivity shown
    → dismissStation() → removeGeofencesForStation() (suspend, retried, worker-backed)
```

---

## Cloudflare Worker

- **URL:** `https://stationalarm-geo.mohammedomama2005.workers.dev/`
- **Endpoints:** `/autocomplete`, `/reverse`, `/search` → LocationIQ v1/* equivalents
- **Caching:** Autocomplete 24h, reverse 1h (Cloudflare edge cache)
- **Auth:** API key injected server-side via `env.LOCATIONIQ_KEY` — never in APK
- **URL is dynamic:** Firebase Remote Config key `geocoding_backend_url` overrides at runtime. The compiled-in default in `RetrofitClient.kt` matches the live Worker, so a missed RC fetch is not a stale-URL hazard.

---

## Database Schema (Room v3)

### `saved_places` table (`SavedPlaceEntity`)
`id, name, lat, lon, radiusKm, notify, vibrate, sound, notes, createdAt`

### `active_stations` table (`ActiveStationEntity`)
Tracks currently active alarms. Status enum: `MONITORING`, `ALERTING`, `PAUSED`.
`PAUSED` is the post-dismiss state — row stays so the user can re-arm without re-configuring.

Migration policy: `.fallbackToDestructiveMigrationFrom(1)` — legacy v1 dev DBs wipe instead of crashing. Bump version + write migration for any schema change going forward.

---

## Graveyard — Rejected Decisions (Do Not Revisit Without Strong Reason)

| Decision | What was rejected | Why |
|---|---|---|
| Mapbox geocoding | Using Mapbox for search | TOS violation — geocoding results cannot be displayed on non-Mapbox maps |
| Rotating API keys | Multiple LocationIQ keys to beat rate limits | TOS risk |
| NaPTAN / EU GTFS / AU feeds | Static station data for Europe/Australia | Scope too large, deferred to Phase 4 |
| Nominatim direct | As primary geocoder | Rate limits too tight for production |
| GADM offline boundaries | Point-in-polygon city tagging | Overkill for current scope |
| Photon via Worker | Routing Photon through Cloudflare | Destroys per-user IP quota, becomes pooled server quota |
| `layers` filter on geocoders | Allowlist of OSM layers in LocationIQ/Photon | Excluded `amenity`/POI results (bus stands, stations, airports) |
| Watchdog as terminal alert | Single 60s stall → loud user-visible notification | Misfired on every transient dip; no self-heal attempted |
| Delete-on-dismiss | Removing the active_stations row when alarm fires | Lost user config; replaced with `PAUSED` state |

---

## Session Log

### Session: Kotlin data class HashCode Drift & LocationService Leak (2026-04-19)
Fixed a massive, yet silent, logic flaw in `LocationService.kt` that was causing severe notification stiction, GPS drain, and breaking the "Stop All" feature.

**The Problem:**
- `LocationService` stored active monitoring stations in a `ConcurrentHashMap.newKeySet<ActiveStation>()`.
- In Kotlin, a `data class` automatically computes its `hashCode()` using all properties in its primary constructor. `ActiveStation` includes `currentDistanceKm`.
- As the device moved, the GPS loop iteratively updated `active.currentDistanceKm`. This continuously mutated the base mathematical `hashCode` of the object *while it was actively seated inside the hash map.*
- When the station transitioned from `MONITORING` to `ALERTING` (or was stopped collectively by "Stop All"), the tracker executed `monitoringStations.removeAll { predicate }`. 
- Because the `hashCode` had drifted from its initial insertion value, the underlying `ConcurrentHashMap` could no longer reliably locate the node to destroy it. 
- The station became an **irremovable orphan** within the memory set. 
- Because `monitoringStations.isNotEmpty()` was permanently artificially inflated to `true` by these orphans, the GPS wake-locks stubbornly refused to shut down, and the foreground notification was permanently plastered with misleading fallback data (e.g., "Nearest station...").

**The Solution:**
- Refactored `monitoringStations` from a `ConcurrentHashMap` KeySet of `ActiveStation` objects directly into a `ConcurrentHashMap<String, ActiveStation>`, keyed exclusively by the invariant property `stationId`.
- The architecture now interacts with `monitoringStations.keys` and `monitoringStations.values`.
- When commanding a removal (`monitoringStations.remove(stationId)`), the lookup is performed against an immutable `String` hash.
- Orphans are eradicated flawlessly. `monitoringStations` reliably drains to absolute zero on alarm, restoring instantaneous GPS teardown and immediately cancelling the sticky persistent notifications once the service yields its responsibilities to the standalone full-screen alarm overlay.

### Session: Architecture Flaws & Re-arming Logic (2026-04-18)
Fixed multiple architectural oversights regarding Geofence lifecycle and `LocationService` State, which caused duplicate alerts and immediate re-firing if alarms were re-armed directly at the destination point. `./gradlew compileDebugKotlin` clean.

**Geofence Lifecycle Scope Fixes**:
- **Atomic Teardown**: `StationRepository.markAlerting` now invokes `GeofenceManager.removeGeofencesForStation(appContext, stationId)` synchronously as part of its block. Outer geofences no longer stay active while an alarm is ringing.
- **Orphan & Boot Reigstration Strictness**: `StationRepository.reconcileOrphans()` and `reRegisterAllGeofencesNow()` now strictly filter via `status == "MONITORING"` (was `status != "PAUSED"`). Restored boot events and cold-starts will no longer re-register geofences for `ALERTING` stations.

**Wait for Exit Pattern & INITIAL_TRIGGER_ENTER isolation**:
- **GeofenceManager split**: `addGeofencesForStation` split into two distinct API requests. Layers 1-5 continue with `setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)` to immediately wake standard polling. The `alert` layer runs via a separate intent with `setInitialTrigger(0)` so GMS triggers it solely upon a physical boundary entrance, NOT immediate registration when already inside.
- **LocationService evaluating re-arms**: Introduced `needsInitialEvaluation` and `waitingForExit` maps. When re-adding a station to `MONITORING`, if the first GPS fix returns a distance smaller than `alertDistanceKm`, the tracker isolates the ID into `waitingForExit`. The tracker gracefully keeps GPS active, drops the false-flag trigger, and waits until `effectiveDistance > alertDistanceKm` to confidently restore normal threat detection logic.

### Session: UI Redesign & Proximity Bar Fix (2026-04-18)
Two user-reported UX issues (drawer steals map drags; proximity bar pre-filled at setup; random UUIDs leaking into the UI) fixed alongside a full M3 pass across the two primary screens. `./gradlew compileDebugKotlin` clean.

**Drawer gesture gate** (`ui/AppRoot.kt`, commit `67d926f`):
- Old gate `gesturesEnabled = currentScreen == Screen.Main` meant any horizontal drag anywhere opened the drawer, hijacking map pan gestures on the Map tab.
- New compound gate: `currentScreen == Screen.Main && (pagerState.currentPage != 1 || drawerState.isOpen)`. Map tab only responds to swipe-to-close (drawer already open) or the menu icon; other tabs keep swipe-to-open.

**M3 redesign** — `StationConfigBottomSheet.kt`, `HomeScreen.kt`:
- Replaced hardcoded `Color.Gray`/`DarkGray`/`Color(0xFFFFEBEE)` with M3 color roles (`primary`/`onPrimary`, `primaryContainer`/`onPrimaryContainer`, `errorContainer`/`onErrorContainer`, `secondaryContainer`, `tertiaryContainer`, `surfaceVariant`, `outline`, `onSurfaceVariant`) — gives correct contrast in both light and dark themes.
- Introduced token files: `ui/theme/Spacing.kt` (8dp grid: xs/sm/md/lg/xl/xxl via `MaterialTheme.spacing`), `ui/theme/Shape.kt` (M3 shape scale), `ui/theme/SemanticColors.kt` (proximity far/near/imminent semantic colors mapped from theme).
- `StationConfigBottomSheet`: three toggle columns → horizontal `FilterChip` row (Notify/Vibrate/Sound); 200-char supporting-text counter on the reminder field; "Show reminder when alarm fires" switch disables when reminder blank.
- `HomeScreen`: alerting card uses `errorContainer`/`onErrorContainer`, paused card uses `surfaceVariant`, status badges use `tertiaryContainer`/`errorContainer`/`outline`, search field has leading/trailing icons (`Search`/`Close`), empty state has circular `primaryContainer` hero.
- Animated color transitions via `animateColorAsState` on proximity bar colors as the phone crosses thresholds.
- **Constraint noted:** `material-icons-extended` is NOT in deps — must stick to core `Icons.Default.*` set. `EditLocationAlt`/`DeleteOutline`/`NotificationsActive`/`Vibration`/`VolumeUp`/`MapsHomeWork` are unavailable; use `Edit`/`Delete`/`Notifications`/`Place` instead (and the FilterChips have no leading icon).

**UUID leak fix** — new `ui/screens/StationDisplay.kt`:
- `displayableStationCode(id)` returns null for `custom-<uuid>` ids (map-dropped pins generated at `MapSearchViewModel.kt:270`), otherwise returns the id (railway codes like `NDLS`, `BCT`). Callers in `StationConfigBottomSheet` and elsewhere only render the code chip when non-null.

**Proximity bar anchor fix** — `HomeScreen.StationCard` → new `ProximityRow` composable:
- **Root cause:** old code set `val anchor = startDistanceKm ?: station.alertDistanceKm`, combined with an `else if (distanceKm <= alertDistance) 1f` branch. Result: on the very first frame after setup — before `LaunchedEffect` had time to observe the live distance and set the anchor — the bar would jump to near-100% whenever the phone was already inside the alert radius (or worse, appear partially filled randomly).
- **Fix:** per-station `remember(stationId) { mutableStateOf<Double?>(null) }` anchor; `LaunchedEffect` grows-but-never-shrinks the anchor as fixes come in. Progress returns 0 until BOTH `distanceKm` AND `anchorKm` are non-null. Once both are set, `progress = (anchor - distanceKm) / (anchor - alertDistanceKm)`, clamped to `[0, 1]`. Bar starts empty, fills as the train covers ground toward the alert radius, saturates to full at the ring.

**Files modified:** `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `ui/theme/Type.kt` (wired up new tokens), `ui/screens/HomeScreen.kt`, `ui/screens/StationConfigBottomSheet.kt`.
**Files added:** `ui/screens/StationDisplay.kt`, `ui/theme/Spacing.kt`, `ui/theme/Shape.kt`, `ui/theme/SemanticColors.kt`.

### Session: Production Readiness Pass 2 — Real-Bug Wave (2026-04-16)
Audit-driven fixes after re-verifying `plans/splendid-mixing-pelican.md` against actual code. `./gradlew compileDebugKotlin` clean.

**Real bugs (Wave 1):**
- **#A Boot restore ordering** — `BootRestoreWorker.kt`: service start now gated on `reRegisterAllGeofencesNow()` returning all-OK. Failed attempts return `Result.retry()` without starting the service, so users no longer see a "Monitoring…" notification while geofences are silently broken. After 5 retries the worker posts a user-visible "Restart needed" notification (high-priority, on the existing tracking channel) and returns `Result.failure()` rather than retrying forever in silence.
- **#B Atomic add + startup reconciliation** — `StationRepository.kt`: both DAO inserts in `addActiveStation()` are wrapped in `database.withTransaction { … }`; the failure-rollback path is also transactional and now also deletes the orphan `saved_places` row when `customStation != null`. New `suspend fun reconcileOrphans()` re-registers geofences for any non-`PAUSED` active station whose registration may have been lost across process death; runs once from `StationAlarmApplication.onCreate()` on `Dispatchers.IO`. `addGeofencesForStation` is idempotent (overwrites by request ID), so reconciliation is safe to run unconditionally.
- **#D Geofence removal retry + persistent cleanup** — `GeofenceManager.kt` (LOCKED, approved): `removeGeofencesForStation` is now `suspend`, with 3 in-process attempts at 500ms → 1s → 2s backoff via `suspendCancellableCoroutine`. On terminal failure it enqueues the new `GeofenceCleanupWorker` (10 attempts, exponential backoff from 30s, unique per stationId, `ExistingWorkPolicy.REPLACE`). The worker calls a new internal `attemptRemoveGeofencesForStation()` that does NOT enqueue, breaking the recursion. Net effect: leaked geofences are no longer possible against the GMS 100/app cap. Immediate re-fire is already blocked by the `isArmed()` PAUSED gate from the prior session, so this is purely a long-tail leak fix.
- **#E Audio terminal-failure escalation** — `AlarmAudioController.kt` (LOCKED, approved): `playAlarmSound` accepts an optional `onAudioTerminalFailure: (() -> Unit)?` callback fired only when both the user-picked URI AND the system default URI fail (the only remaining "silent alarm" path). `LocationService.fireAlert` (LOCKED, approved) passes a callback that starts the vibrator (if not already vibrating) and calls the new `notifications.showAudioFailureNotification(stationLabel)` (`ServiceNotifications.kt`, LOCKED, approved — high-priority, ongoing, "Alarm fired silently — audio system error at <name>"). Both alarm-teardown branches in `LocationService` (sync auto-prune in `syncWithDatabase` and `handleDismiss`) now also call `cancelAudioFailureNotification()` so the notice doesn't linger after dismiss.

**Polish (Wave 2):**
- **#F SharedFlow buffer** — `StationRepository.errorEvents`: bumped `extraBufferCapacity` to 16 with `BufferOverflow.DROP_OLDEST` so error bursts (e.g., max-cap → re-add → max-cap) don't suspend the producer.
- **#J First-frame station name** — `ServiceNotifications.showAlert` puts `EXTRA_STATION_NAME` (new `companion object` constant) into the full-screen intent. `AlarmActivity.onCreate` reads it and seeds `stationName` state from it, so the first frame shows the real name instead of briefly flashing the raw `stationId` while the async DB lookup loads.
- **#L Invalid coords** — `GeocodingModels.kt`: `toSearchResult()` mappers parse to `Double.NaN` instead of `0.0` on failure; new `GeoSearchResult.hasValidCoords` checks NaN + range + not-(0,0). `MapSearchViewModel` filters results through `hasValidCoords` so degenerate rows can't sneak into the UI as "Null Island" pins.
- **#M Typed network errors** — `MapSearchViewModel.errorMessageFor(Throwable)` branches on `IOException`, HTTP 429, 500..599, default; surfaced via `_searchError`. The location-FAB path also now handles null fix and `addOnFailureListener` with user-readable copy ("No recent location fix" / "Couldn't read your location").
- **#O SAF URI reachability** — `SettingsScreen.kt`: alarm sound row probes the persisted URI via `contentResolver.openInputStream(uri)?.use { true }` on entry; if it fails, shows a "⚠ Sound unavailable — re-select to fix." line in the error color and switches the button label to "Re-select". Complements the fire-path validation already in `LocationService.resolveValidatedAlarmUri`.
- **#Q DataStore writes** — `UserPreferences.kt`: all three setters wrap their `edit { … }` calls in a `runSafely(op, block)` helper that swallows IOException with a Log.e instead of crashing the caller.
- **#V Station data load** — `StationData.kt`: catch path now logs `STATION_DATA_LOAD_FAILED`; null/empty parse logs `STATION_DATA_EMPTY`. Search fallback unchanged — silent empty list was the visible-only failure mode before.
- **2nd-pass:** custom-reminder `OutlinedTextField` in `StationConfigBottomSheet` capped at 200 chars in `onValueChange` (notification body and alarm card both show the full string, so longer text was a cosmetic + DB-bloat hazard).

**Verified-not-bug (per audit recheck):**
- `processLocationUpdate` NPE — already null-safe via `?: continue`.
- Receiver init race — `StationRepository.initialize()` runs synchronously in `Application.onCreate` before any broadcast can land.
- Background-loc not rechecked — `AppRoot.kt` already rechecks on `ON_RESUME`.
- Notification-rationale re-show — intentional per user.

**LOCKED-file changes this session (all approved by user's "carry out all the necessary bug fixes"):** `GeofenceManager.kt`, `AlarmAudioController.kt`, `ServiceNotifications.kt`, `LocationService.kt`.

**New file:** `geofence/GeofenceCleanupWorker.kt`.

### Session: Self-Healing GPS Watchdog (2026-04-16)
Old watchdog treated any 60s stall as terminal and posted a user-visible high-priority notification, so any transient dip on a long trip misfired. Redesigned as a **self-healing status indicator** with tiered escalation. `./gradlew compileDebugKotlin` clean.

- `ServiceNotifications.showWatchdog(title, body)` (LOCKED — approved): now ongoing, `PRIORITY_LOW`, `setOnlyAlertOnce(true)`. Caller supplies copy per cause. Added `cancelWatchdog()`.
- `LocationService.handleWatchdogTick()` (LOCKED — approved):
  - **Tier 0 (healthy):** stall ≤ `max(60s, 3× currentPollingIntervalMs)` → noop.
  - **Tier 1 (silent recovery):** Tier 0 < stall < Tier 2 → `restartLocationUpdates()` + one-shot `getCurrentLocation(PRIORITY_HIGH_ACCURACY, token)`. No UI.
  - **Tier 2 (notify + keep fighting):** stall ≥ `max(180s, 2× currentPollingIntervalMs)` **or** GPS provider off. Copy depends on cause. Forces polling floor to 10s, re-kicks one-shot recovery each tick. Notification re-posts silently with updated age.
  - **Doze suppression:** `PowerManager.isDeviceIdleMode` (true system doze, not screen-off alone) keeps it in silent mode unless GPS is off.
- **Recovery:** any incoming fix in `onLocationResult` (or the one-shot success listener) clears watchdog state and calls `cancelWatchdog()`. Notification disappears automatically — user never has to tap.
- New log events: `WATCHDOG_SELF_HEAL`, `WATCHDOG_TRIGGERED` (with cause), `WATCHDOG_RECOVERED`, `WATCHDOG_RECOVERY_FAILED`.

**Why:** treat the watchdog as a state indicator, not an event. Alarm is never abandoned — polling forced to 10s during Tier 2, recovery kicks continuously.

### Session: Alarm Reliability Fixes (2026-04-15)
Four user-reported critical issues fixed; `./gradlew compileDebugKotlin` clean. Architectural decisions worth preserving:

1. **Re-fire-after-dismiss gate (`isArmed`)** — `GeofenceBroadcastReceiver` previously used `isActive`, which returned true for any row including `PAUSED`, so geofence events queued in GMS during the async `removeGeofencesForStation` window slipped through and re-triggered the alarm. New `StationRepository.isArmed(stationId): Boolean` (false for missing rows or `PAUSED`) is now the single arming check; `ActiveStationDao.getStatus` added to back it.
2. **GPS gate parity** — `AppRoot.onToggleStation` and `onEditStation` now have the same `isGpsEnabled()` gate that `onStationSelected` and `onStartTrip` already had. All four arming paths consistently raise `showGpsDialog` on failure.
3. **Full teardown on last alarm** — `LocationService.syncWithDatabase` (LOCKED — approved) full-empty branch explicitly: `stopLocationUpdates()` (releases fused client + GPS wake lock) → `stopForeground(STOP_FOREGROUND_REMOVE)` → `notifications.cancelForeground()` → `stopSelf()`. Skips the re-notify path entirely on shutdown. `cancelForeground()` is required because `stopForeground(REMOVE)` alone races with a prior `notifyForeground` and leaves the notification visible. Map/config screens use FusedLocation independently of `LocationService`, so this only affects the no-armed-stations case.
4. **Smooth proximity bar** — `HomeScreen.StationCard` bar now anchored to the **largest observed distance per stationId** (remember + LaunchedEffect that grows but never shrinks), wrapped in `animateFloatAsState(tween 900ms)` so polling intervals don't look like teleport jumps.

### Session: Production Readiness Pass 1 (commit `72a360d`, 2026-04-14)
Implemented all "TO-DO IMMEDIATELY" items from `plans/production-readiness.md`. Architecturally meaningful pieces:

- **Signing**: `app/build.gradle.kts` reads optional `keystore.properties` (gitignored along with `*.keystore`/`*.jks`); missing file → unsigned release so debug builds still work.
- **Log hygiene**: `RetrofitClient.kt` only registers `HttpLoggingInterceptor` when `BuildConfig.DEBUG`.
- **Cached alarm URI (LOCKED)**: `LocationService.observeAlarmSoundUri()` collects `UserPreferences.alarmSoundUriFlow` in `onCreate` into `@Volatile cachedAlarmSoundUri`. `fireAlert` reads the var — no more `runBlocking` on Main dispatcher.
- **URI revalidation (LOCKED)**: `LocationService.resolveValidatedAlarmUri()` probes `contentResolver.openInputStream(uri)` before passing to audio; revoked SAF perms / deleted files fall back to default.
- **Geofence Result-typing (LOCKED)**: `GeofenceManager.addGeofencesForStation` returns `Result<Unit>` with `GeofenceRegistrationException` (`MissingPermission`, `StationNotFound`, `MaxStationsReached`, `RegistrationFailed`). `StationRepository` pre-checks max cap, rolls back on failure, emits user-facing strings via `errorEvents: SharedFlow<String>`. `rearmStation()` reverts to PAUSED if re-registration fails (toggle can't lie). Suspend `reRegisterAllGeofencesNow(): Boolean` exists for `BootRestoreWorker`.
- **Notification-permission rationale**: `AppRoot.kt` tracks `POST_NOTIFICATIONS` separately on Android 13+, deep-links to `Settings.ACTION_APP_NOTIFICATION_SETTINGS`, re-checks on `ON_RESUME`.
- **Boot restore via WorkManager**: `BootReceiver` enqueues `OneTimeWorkRequest` with `ExistingWorkPolicy.KEEP` + `BackoffPolicy.EXPONENTIAL`. Worker resets stale `ALERTING → MONITORING` (ALERTING is treated as a transient state — anything still ALERTING at boot is a leftover from a crashed/killed dismiss path), calls `reRegisterAllGeofencesNow()`, starts `LocationService`. Dep added: `androidx.work:work-runtime-ktx:2.9.1`.
- **Versioning**: `versionCode = gitCommitCount()` (`git rev-list --count HEAD`, falls back to 1); `versionName = "1.0.$appVersionCode"`.

### Session: Legal Pages Authored (2026-04-14)
- New standalone repo at `C:\Users\omama\AndroidStudioProjects\stationalarm-legal\` (not yet on GitHub). `privacy.html`, `terms.html`, `index.html`, `style.css` — light/dark via `prefers-color-scheme`.
- Two placeholder tokens to find-replace before deploy: `<dedicated-email-placeholder>`, `<effective-date-placeholder>`.
- Initial commit `4754390`. `AboutScreen.kt` URL constants point to `stationalarm-legal.pages.dev` (correct target).
- **External next steps** (manual, outside Claude): create public GitHub repo `stationalarm-legal`, Cloudflare Pages → Connect to Git → no framework/build, deploy. Then create dedicated Gmail and find-replace both placeholder tokens.

### Session: Production Setup & Security Audit (2026-04-10)
- Firebase integration: `google-services.json` secured via `.gitignore`.
- Security audit: `git ls-files` + grep — zero API keys, tracking IDs, or keystores tracked.
- Remote Config: `geocoding_backend_url` is the only RC key in use.

### Session: Modularity Refactor (2026-04-07)
`service/LocationService.kt` slimmed 819 → ~430 lines. Extracted same-package: `AlarmAudioController.kt`, `ServiceWakeLocks.kt`, `ServiceNotifications.kt`. UI extractions: `OsmMapView.kt` out of `MapSearchScreen.kt`; `AppRoot.kt` + `AppNavigation` out of `MainActivity.kt`. Current state of these files is the canonical record — see code, not log entries.

---

## Pending

### Must Do Before Play Store / Field Trust
- **Create release keystore:** Generate `.keystore`, write `keystore.properties` (storeFile/storePassword/keyAlias/keyPassword) to project root, confirm `./gradlew assembleRelease` produces a signed AAB.
- **Deploy legal pages:** Push `stationalarm-legal` repo to GitHub, connect Cloudflare Pages, verify `/privacy` + `/terms` load. Find-replace both placeholder tokens.
- **Battery optimization onboarding:** First-launch dialog + deep-link to `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (prevents "missed alarm" reviews on MIUI/OneUI).
- **Localization:** Hardcoded strings → `strings.xml`. Foreground-service notification text in locked `LocationService.kt` still hardcodes "km" — only changes if explicitly approved.

### On-Device Verification (since last device sweep)
- **Boot restore (Pass 2):** Reboot with armed stations + airplane mode → confirm `BootRestoreWorker` retries without starting the service; re-enable network → restore completes. Reboot 5+ times rapidly while offline → "Restart needed" notification appears.
- **Reconcile orphans:** `adb shell am force-stop` mid-`addActiveStation` → relaunch → `RECONCILED` log fires, alarm fires correctly on test trip.
- **Geofence cleanup worker:** Dismiss alarm under airplane mode → re-enable network → check logcat for `GEOFENCE_CLEANUP_OK` from worker.
- **Audio terminal-failure escalation:** Force the rare double-failure path → confirm vibration starts and "Alarm fired silently" notification appears.
- **Watchdog tiers:** Long ride with brief signal dips → no notification (Tier 1 self-heal). Sustained loss → Tier 2 notification appears, vanishes on first fix.
- **High-velocity polling:** Mock at 150–250 km/h → adaptive tracker fires before passing threshold.
- **Failover chaos drill:** Misconfigure RC URL to force 502 → Photon takes over.

### Low Priority (noted, not scheduled)
- `StationConfigBottomSheet` param `initialNotes` → rename to `customReminder`.
- "Remove all alarms" feature.
- Europe/Australia station data (NAP/GTFS) — Phase 4.

### Future (Phase 4)
- Play Store submission.
- Europe station data (NAP/GTFS).
