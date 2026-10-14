# StationAlarm — Play Store Readiness Report

**Date:** 2026-10-14 · **Build audited:** `main` @ `cf99b02` (versionName 1.0.90) · **Scope:** English-only launch
**Verdict: NOT ready to publish.** There are 3 crash/data-loss defects (2 reproduced on a real minified build), 1 dead legal link, and several Play-policy gaps. All are fixable; none requires a redesign. A realistic path to production is **~4–6 weeks**, dominated by Play's mandatory closed-test period, not by engineering.

> **Evidence tags used below** — **[RUN]** reproduced by running the app (Android 16 / API 36.1 emulator, minified release build) · **[BUILD]** produced by Gradle/lint/aapt · **[CODE]** found by reading source (not executed) · **[MODEL]** from my simulation (`scratchpad/sim_alarm.py`, assumptions stated) · **[WEB]** fetched from the live internet.
> This report was written first (run-based). A second, **static trace** of every file and user workflow followed — see [`docs/ALARM_ENGINE_TRACE.md`](docs/ALARM_ENGINE_TRACE.md) (misfires, missed alarms, power-cycle behaviour) and [`docs/DESIGN_AND_UX_REVIEW.md`](docs/DESIGN_AND_UX_REVIEW.md) (what the app should be). Where the trace corrected something below, the item says so.
> Fixes to non-locked files are now in the repository (§0); changes to the behaviour-locked files are **proposals awaiting your approval** (trace doc §8).

---

## 0. Status after the latest commit

| Item | Status |
|---|---|
| B2 R8 / Gson `TypeToken` | **fixed** (rule added; proven on the minified build) |
| B5 advertising-ID permissions | **fixed** (stripped; confirmed absent from the final minified APK) |
| B7 OSM attribution | **fixed** (`CopyrightOverlay`; visibility to be confirmed in the next simulation) |
| **B9 geofence IDs with `_` (new)** | **fixed for new alarms** (IDs sanitised); receiver hardening proposed |
| R9 search ranking | **fixed** (ranked, New Delhi no longer cut off) |
| Map-tab state bugs (stale search, tap race, pin snap, error flash) | **fixed** |
| Boot service start / notification flash with only paused alarms | **fixed** |
| Geofence-cleanup job deleting re-armed alarms' geofences | **fixed** |
| B1 boot crash (armed alarm), B3 Android 8 crash | **proposed** — need your approval (locked files); B1's fix was incomplete when first "verified" (see below) |
| B4 legal site / privacy policy, B6 icon, B8 signing key, Play declarations | **open** — not code in this repo |
| R1–R8, R10–R11 and the new findings | see the trace doc (each has a status) |

Unit tests: **60 / 60 pass** (39 existing + 21 new) with the non-locked changes.

---

## 1. GitHub status

| Check | Result |
|---|---|
| `origin/main` vs local `main` | **0 behind, 5 ahead.** Nothing to pull. |
| Unpushed commits | `2f47228` (19 translations), `2627ef3`, `79ccf81` (docs), `599abf6` (README), `cf99b02` (merge glass-ui). **Push is yours to trigger** — I did not push. |
| Untracked | `.agents/`, `.idea/markdown.xml`, `graphify-out/` (1.1 MB `graph.json` + 1 MB `graph.html` — add to `.gitignore` unless you want them versioned). |
| Stray tracked files | `replace.py`, `test.kt` (one-off scratch scripts), partial `.idea/`. An empty directory literally named `C:UsersomamaAndroidStudioProjectsStationAlarm-Backend` sits in the repo root (untracked; a mangled-path accident). |
| Docs drift | `PROJECT_STATE.md` says "Last updated 2026-06-12", DB "v6", and still lists "Worker not yet redeployed" (it **is** live and correct — [WEB]). It does not mention the glass-UI merge or the English-only decision. |

**Graph (`graphify-out/GRAPH_REPORT.md`)** was built after the last commit, so it is current: 908 nodes / 2,026 edges / no import cycles. God nodes: `LocationService` (50 edges), `StationRepository` (41), `AlarmAudioController` (37). Cohesion of the geofence/repository community is low (0.06) — consistent with what I found: engine state is spread across the service's in-memory maps, the DB and GMS, and they drift (see R1–R3). *(I did not have a `/graphify` skill in this session, so I used the existing report rather than regenerating it.)*

---

## 2. What was done

| Activity | Outcome |
|---|---|
| Read all engine/UI/data code (locked files included) | ~10.4 k LOC; findings below |
| `testDebugUnitTest` | **39/39 pass** — but **zero** tests touch the alarm engine, receivers, geofences or repository |
| `lintDebug` / `lintRelease` | **1 real error** (B3) + 131 mostly cosmetic warnings |
| `assembleRelease` + `bundleRelease` from a **clean clone** | **Builds** (APK 4.2 MB, AAB 7.9 MB, R8 on). Proves a fresh checkout is reproducible. |
| 16 KB page-size check on native libs | **Pass** (`zipalign -P 16`) |
| Installed the real minified release on an emulator and drove it with injected GPS | Found B1 and B2; confirmed happy-path alarm works |
| Alarm-latency model (gearbox × geofence rings × FLP batching × speed) | §5 |
| Live checks: Cloudflare Worker, legal site, station dataset | Worker OK; **legal site does not exist (B4)**; dataset clean but search ranking poor (R9) |

**Honest limits.** No physical phone and no Xiaomi/Samsung/OnePlus ROMs, so OEM-killer behaviour, real GNSS, Bluetooth routing and real train motion are **untested**. The emulator on this machine was unstable under load (8 GB RAM, **C: drive 100 % full — 0 bytes free**); it hung twice and one ANR it showed was emulator starvation, not an app defect (I checked the ANR reason). C: being full also made `assembleRelease` fail with "not enough space on the disk" until I built on D:. **Free space on C: before you do anything else.**

---

## 3. Findings

### P0 — Blockers (crash, silent loss of alarms, or certain Play rejection)

#### B1 · App crashes on every reboot while an alarm is armed (Android 15+) — **[RUN]**
`ForegroundServiceStartNotAllowedException: FGS type mediaPlayback not allowed to start from BOOT_COMPLETED!` at `LocationService.updateForegroundNotification` (`LocationService.kt:858`, called from `onStartCommand:298`).
- **Cause:** on a cold service start `monitoringStations` is still empty (DB sync is async), so `newIsLocationActive` is `false` and the first `startForeground()` picks `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`. Android 15+ forbids that type from a boot-launched context.
- **Observed sequence:** reboot → `BootRestoreWorker` re-registers geofences and logs **"Restored 1 stations OK"** (so it *reports success*) → service crashes → system restarts it 37 s later → crashes again → stays dead. No foreground notification, no GPS polling, **UI still shows the alarm as armed.** Opening the app recovers it (foreground start is allowed). Crashlytics will record this as a **fatal** crash for every affected user on every reboot — it will wreck crash-free-user stats.
- **Fix (1 line, `LocationService.kt` is LOCKED → needs your approval):** treat the initial `startForeground` as `LOCATION` unless an alarm is already ringing: `val newIsLocationActive = monitoringStations.isNotEmpty() || (forceStartForeground && alertingStationIds.isEmpty())`. Also wrap `startForeground` in `try/catch(ForegroundServiceStartNotAllowedException)` so a platform refusal can never kill the process, and make `BootRestoreWorker` not report success when the service failed to start.
- **Verified:** the one-line change above removes the crash on the same reboot test (§8). [RUN]
- **Correction from the static trace:** that one-liner is **incomplete**. It passed on the emulator only because location permission was granted. With a `location` foreground type, Android 14+ throws `SecurityException` from `startForeground()` when no location permission is held (and `AppRoot` starts the service even after a permission-denied arm). It also changed the 3-second *Test alarm* to `location`. The complete fix (patch **L1** in the trace doc) checks the permission, keeps `mediaPlayback` for the test alarm, and catches platform refusals so they can never crash the service. Also: the same boot start was attempted for users whose alarms were **all paused** — that part is now fixed in the repo (the worker and `onResume` only act on armed alarms).

#### B2 · Release build cannot load the offline station database — **[RUN]**
Log from the shipped-config build: `STATION_DATA_LOAD_FAILED: TypeToken must be created with a type argument … When using code shrinkers (R8) make sure generic signatures are preserved.` Result: **no station is ever found** in Home search or Map tier-1. Debug builds and all 39 unit tests pass, so nothing warns you.
- **Fix:** add to `proguard-rules.pro`
  `-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken` and `-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken` (or parse with `Array<Station>::class.java` and avoid `TypeToken`).
- **Verified:** rebuilt release with those two lines → "PUNE" returns **PUNE JN**, and no new `STATION_DATA_LOAD_FAILED` in the session log. [RUN]
- **Process lesson:** you have never been testing the build you ship. Add a smoke test that runs against the minified build (§7).

#### B9 · Hardware geofences never work for geocoded / dropped-pin alarms — **[TRACE]** (found in the static trace)
Geofence request IDs are `geofence_<stationId>_<layer>` and `GeofenceBroadcastReceiver` splits them with `split("_", limit = 3)`. Geocoder IDs look like `liq_481312177`, so the receiver reads station `liq` and layer `481312177_alert`, finds nothing armed, and **drops every geofence event** — the wake-up rings *and* the alert geofence. Result: every Map-tab alarm relied on GPS polling alone (and, since the offline list is India-only, that is every alarm outside India).
- **Fixed in the repo:** IDs are sanitised (`liq-481312177`) at the view-model and again at the repository; `StationIdsTest` reproduces the split.
- **Proposed (locked file, patch L6):** parse from the end of the ID so any alarm created earlier also works.

#### B3 · Alarm path crashes on Android 8.0/8.1 (minSdk 26) — **[BUILD]**
Lint `NewApi` error: `MediaPlayer.setPreferredDevice` needs API 28. On API 26/27 it throws `NoSuchMethodError`, which the surrounding `catch (e: Exception)` in `AlarmAudioController.createRoutePlayer` does **not** catch (it's an `Error`) → the service dies **at the moment the alarm should ring**.
- **Fix options:** raise `minSdk` to 28 (simplest; Android 8 is <3 % of devices and Play drops it for most of your audience anyway) **or** guard with `Build.VERSION.SDK_INT >= 28` in the locked `AlarmAudioController.kt` (behaviour-preserving on ≥28). I recommend the guard plus keeping 26 only if you've a reason to.

#### B4 · Privacy-policy URL does not exist, and the policy is inaccurate — **[WEB]**
- `stationalarm-legal.pages.dev` returns **NXDOMAIN**. The in-app About screen links to it, and Play **requires** a live privacy-policy URL for any app requesting location. The repo `stationalarm-legal` exists only locally (no git remote) with the placeholders `<dedicated-email-placeholder>` and `<effective-date-placeholder>` still in `index.html`, `privacy.html`, `terms.html`.
- Content is wrong vs. the app and would itself be a policy violation:
  1. *"your current GPS position is not attached"* to searches — **false**: `bias_lat/bias_lon` (last known location) go to the Worker on every search, and tapped map points go to `/reverse`.
  2. Describes a **Photon fallback that was removed** in May.
  3. *"No personal identifiers … no advertising identifiers"* — Firebase Analytics/Crashlytics use a persistent app-instance ID, and the merged manifest declares `AD_ID` (B5).
  4. Doesn't disclose that GPS traces are written to a file in public `Downloads` (R7), or the background-location behaviour.
- **Fix:** publish the site (Cloudflare Pages, push repo to GitHub), replace placeholders, rewrite §1/§3 to match reality, add a data-deletion contact.

#### B5 · Advertising-ID permissions in the manifest contradict "no ads" — **[BUILD]**
`aapt2` on the release APK shows `com.google.android.gms.permission.AD_ID`, `ACCESS_ADSERVICES_AD_ID`, `ACCESS_ADSERVICES_ATTRIBUTION` (pulled in by `firebase-analytics`). Play Console will ask whether you use the advertising ID; "No" + a manifest that declares it is flagged.
- **Fix:** `<uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove"/>` (and the two ADSERVICES ones) and, if you want, `google_analytics_adid_collection_enabled=false`. Then answer "No" in Console.

#### B6 · Launcher icon is the unmodified Android Studio template — **[CODE]**
`ic_launcher_foreground.xml` is the stock robot/green-grid vector; `AboutScreen` uses `Icons.Default.Info` as its "logo"; every notification uses `android.R.drawable.ic_dialog_info/alert` (visible in my alarm screenshot as the green Android robot). Not a policy violation per se, but it reads as a template app and weakens the store listing. Needs a real icon, a monochrome notification glyph, and a feature graphic.

#### B7 · No OpenStreetMap attribution — **[CODE]**
Only "Search by LocationIQ.com" is shown. OSM's tile-usage policy and the ODbL require visible **"© OpenStreetMap contributors"** on any map using its data/tiles. Add osmdroid's `CopyrightOverlay` (or a text label). Cheap and mandatory.

#### B8 · No release signing key / Play App Signing set up — **[CODE]**
`keystore.properties` doesn't exist; release currently builds **unsigned**. Create the upload key, back it up off this machine, enrol in Play App Signing.

---

### P1 — Must fix before production (reliability and trust)

#### R1 · Editing an armed alarm's radius/sound doesn't reach the running service — **[CODE]**
`syncWithDatabase` only adds stations whose `stationId` is *new* to `monitoringStations`; existing entries are never refreshed (`LocationService.kt:409-420`). After **Edit**, `updateActiveStationSettings` rewrites the DB row and re-registers geofences at the **new** radius, but the GPS evaluator keeps comparing against the **old** `alertDistanceKm`.
- *Edit 10 km → 3 km:* GPS path fires at 10 km (too early) while the geofence is at 3 km.
- *Edit 3 km → 10 km:* GPS path waits until 3 km; only the (higher-latency) geofence covers 10 km.
- *(Correction: I first suspected `updateActiveStationSettings` could re-register geofences for a PAUSED station. The Edit button is disabled for paused cards, so that path is not reachable from the UI.)*
- **Fix (locked file):** in the sync step, replace entries whose DB row differs (`monitoringStations[id] = fresh.copy(currentDistanceKm = old.currentDistanceKm)`).
- *Not reproduced live* — the emulator became too slow before I could run it; this is straight from the code, high confidence. Add a test.

#### R2 · After a reboot / service restart *inside* the radius the alarm never fires — **[CODE, partly RUN]**
Every station the service sees for the first time (including after process death or reboot) goes into `needsInitialEvaluation`; if the first fix is already inside the radius it is added to `waitingForExit` and **suppressed until you leave and re-enter**. That is right when the *user* re-arms inside the radius (I observed `WAITING_FOR_EXIT … Re-armed while inside radius`, no alarm — [RUN]), but wrong for **"phone died at 50 km, you boot it 2 km from the station"** — the exact scenario you asked about. The alert geofence uses `setInitialTrigger(0)`, so it won't fire either.
- **Fix:** only apply the wait-for-exit rule when the row was re-armed by the user (stamp a `armedAt` / `rearmedByUser` flag when `rearmStation`/`addActiveStation` run), not for restarts. Needs product decision: after a reboot inside the radius, ring immediately.

#### R3 · Nothing re-arms the engine after an app update — **[CODE]**
Manifest has no `MY_PACKAGE_REPLACED` receiver. A Play auto-update kills the process; geofence registrations are tied to the old install's state, and the service isn't restarted. Armed alarms stay dead until the user opens the app. Add a receiver that enqueues the same `BootRestoreWorker` (with the B1 fix).

#### R4 · If Google geofencing is unavailable the alarm cannot be created, with a cryptic error — **[RUN]**
With *Google Location Accuracy* off (also common on real phones), `addGeofences` fails with GMS status **1000** (`GEOFENCE_NOT_AVAILABLE`). The repository rolls the alarm back and surfaces `e.message`, which is literally **`"1000: "`**. The GPS polling path would have worked fine.
- **Fix:** don't roll back on 1000/1004 — keep the alarm armed in "GPS-only" mode, warn the user once ("Turn on Location Accuracy for earlier wake-up"), and deep-link with the existing `LocationSettingsRequest`. Map every `GeofenceStatusCodes` value to plain English.

#### R5 · The GPS safety net is weaker than it looks at speed — **[MODEL]**
The hardware **alert geofence does most of the work** at train speeds; GPS polling is the backstop. Two settings make the backstop weak: (a) the gearbox is **distance-only** (10 min polling beyond 60 km, 5 min to 30 km) and (b) `setMaxUpdateDelayMillis(2 × interval)` lets FLP defer a 10-minute sample by up to 20 minutes. If geofence delivery fails (OEM kill, GMS throttling, location-accuracy off), **GPS-only misses at ≥160 km/h under typical batching, and at ≥100 km/h under worst-case batching** (§5). A speed-aware gear (`interval ≈ time-to-alert / 4`, clamped 10 s–10 min) with batching ≤ 1× removes every miss in the model up to 300 km/h at similar far-range battery cost. Also observed [RUN]: geofence ring latency was **~106 s** in one step and <1 s in another — so don't assume geofences are instant.

#### R6 · Alarm gives up after 3 min, and a stray key press ends it — **[CODE]**
- `startTimeout` auto-dismisses after `alarm_duration` (default 180 s) → station goes **PAUSED**; a sleeper who didn't wake gets nothing further. Suggest: re-ring every N minutes while the user is still within X km, or leave it ringing until dismissed.
- `AlarmActivity` dismisses on **Volume up/down/mute and Back**. In a pocket/bag, or with Bluetooth buds, that's an accidental dismissal that also PAUSES the alarm. Make hardware keys *silence* (not dismiss), require an on-screen action.
- The 10-minute alarm wake-lock cap is shorter than the 15-minute maximum alarm duration setting.

#### R7 · Precise GPS traces are written to public Downloads, unbounded, in release — **[RUN]**
`Logger` writes `Downloads/TrainAlarmLogs/TrainAlert_<ts>.csv` **per process start**; `GpsLogger` appends lat/lon/speed/accuracy/battery/model to `GpsThrottle_TestLog.csv` forever. Neither is gated on `BuildConfig.DEBUG`; no rotation. In ~40 minutes of testing I got **4 session files and 4 copies** of the GPS file (`… (1).csv`, `(2)`, `(3)` — scoped storage re-creating them after reinstall). Privacy exposure + storage growth (≈ 2 MB/day at 5 s polling) + undeclared in the policy. Also the Settings screen exposes "Debug Logs" to every user.
- **Fix:** move to app-private storage, cap at e.g. 1 MB with rotation, enable only via a hidden toggle/debug build, disclose in the policy.

#### R8 · Location-permission denial is a dead end — **[CODE]**
`AppRoot` re-launches the foreground permission request on every `ON_RESUME`; if the user denied twice (or picked **Approximate** only) nothing explains why the app does nothing and there's no deep-link to Settings. The background-location overlay is a full-screen blocker with only "Open Settings" (no decline path). Play requires the permission flow to degrade gracefully and the disclosure to appear *before* the request, state that location is collected even when the app is closed, and offer an affirmative choice.

#### R9 · Station search ordering hid the station people want — **[FIXED]**
Tier-1 returned the **first 8 matches in file order**, unranked. `"delhi"` has 11 matches and **New Delhi (NDLS) was the 11th, so it was cut off.** *(Dataset naming/aliases are out of scope by your instruction; this is about the code's ordering and cut-off only.)*
- **Fixed:** results are ranked (exact code → exact name → whole word → word prefix → code prefix → contains), shorter names first, up to 20. Verified against the real list with a script before writing the Kotlin: "delhi" → New Delhi is now #2; exact codes always rank first; an exact-code regression check passes; `StationIndexTest` covers it.
- Still open (small): the Map tab needs ≥ 3 characters while Home says "at least 2" — pick one.

#### R10 · Full-screen alarm isn't guaranteed — **[RUN/CODE]**
- With the app in the foreground the alarm is only a **heads-up notification** (verified on screen); `AlarmActivity` is not launched.
- On Android 14+ `USE_FULL_SCREEN_INTENT` can be revoked; the app never calls `NotificationManager.canUseFullScreenIntent()` or sends the user to `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. Play now requires a declaration that you are an alarm/calling app.
- `AlarmActivity` ignores `onNewIntent`, so when a second station fires while the first screen is up, the screen can only dismiss the first.

#### R11 · No safety net in the repo for the engine — **[CODE]**
No tests for `LocationService`, `GeofenceBroadcastReceiver`, `BootRestoreWorker`, `StationRepository` state transitions or the gearbox (all private inside the locked service). One placeholder instrumented test. No CI. B1, B2 and R1 would each have been caught by a 20-line test.

---

### P2 — Should fix (quality, polish, hygiene)
- **GPS wake lock** has a hard 4 h timeout and is only re-acquired when updates restart (`ServiceWakeLocks.acquireGps`). Trains run 12–36 h. Re-acquire on each watchdog tick.
- `MainActivity.onResume` starts the foreground service whenever *any* row exists — including paused-only — flashing a foreground notification then stopping; `requestFreshLocation()` fetches a fix and throws it away.
- The **Notify** chip is never read by the engine (notification always shown) — either wire it up or remove it.
- **Backups:** `allowBackup=true` with template rules → Room DB + DataStore are cloud-backed-up and restored on a new phone as "armed" alarms with no geofences. Exclude them or set `allowBackup=false`.
- Fix `ALERT_FIRED … distance=null` (telemetry always null).
- `FixQualityGate` docs say a demoted fix "cannot trigger the alarm", but subtracting a larger accuracy from the distance makes it **easier** to trigger (≤ 1 km early with coarse cell fixes). Fix the comment or the behaviour.
- `AudioManager.setStreamVolume` in `onPrepared` is unguarded (can throw `SecurityException` on some ROMs → crash during alarm).
- Alert radius range is 3–20 km: at 160 km/h, 3 km ≈ 67 s of warning; a sleeper wanting 30 min on an express needs ~55 km. Consider 3–50 km and a "minutes before arrival" option. Map ViewModel default radius (3) ≠ sheet default (5).
- Plurals ("station(s)"), `UseKtx`, 63 ellipsis warnings, 17 version-catalog hints, 10 unused resources, `StaticFieldLeak` on loggers (use app context — fine, but suppress).
- `RetrofitClient`: RC-supplied base URL means whoever controls Firebase Remote Config receives every user's search + location. Fine, but pin/allow-list the host.
- **OSM tile server** (`tile.openstreetmap.org`) is not for production-scale apps; plan a commercial/self-hosted tile source before growth.
- `versionCode = git rev-list --count HEAD` breaks on shallow CI clones and on squash/rebase (it can go **down**, which Play rejects). Use a committed counter.
- `runBlocking` DataStore read in `Application.onCreate` (goes away with the English-only cleanup).
- `local.properties` contains a leftover `MAPBOX_ACCESS_TOKEN` (not committed, not referenced). Delete it; your rules forbid Mapbox.
- The Worker URL and the `workers.dev` account name are baked into the APK; fine, but note it's public.

---

## 4. "What happens when…" — scenario matrix

| # | Scenario | Result | Basis |
|---|---|---|---|
| 1 | Create alarm, drive in, dismiss | **Works.** `ALERT_FIRED` → alarm channel notification, audio focus taken, dismiss stops sound, removes foreground notification, stops service, row → PAUSED, geofences removed. | [RUN] |
| 2 | Same, but search for the station on the shipped build | **No stations found** (B2) | [RUN] |
| 3 | Phone dies, later boots **far** from station | Geofences re-registered, but service **crashes** (B1); UI says armed; recovers when app opened | [RUN] |
| 4 | Phone dies, boots **inside** the radius | Crash (B1) and, once fixed, alarm suppressed by wait-for-exit (R2) | [RUN + CODE] |
| 5 | Re-arm while already inside the radius | Silent by design; no UI explains why | [RUN] |
| 6 | Edit radius while armed | Service uses stale radius (R1) | [CODE] |
| 7 | Google Location Accuracy off | Alarm can't be created; raw "1000:" error (R4) | [RUN] |
| 8 | Swipe app from Recents | `onTaskRemoved` restarts service; OK on stock Android | [CODE] |
| 9 | OEM "Clear all"/Force-stop (Xiaomi, Oppo, Vivo, Realme) | Android removes alarms/geofences/services; **nothing restarts the app**. Unfixable in code — needs onboarding for Autostart/battery lock | [CODE] |
| 10 | App update from Play | Engine dead until next app open (R3) | [CODE] |
| 11 | Location switched off mid-trip | Watchdog posts "Location is off" low-priority notification; keeps retrying; recovers on fix | [CODE] |
| 12 | Doze / screen off on a train | GPS wake lock held ≤ 4 h (P2); geofence rings are the safety net | [CODE] |
| 13 | Flight mode / no data | Alarm works (GPS + geofences are offline). Search and map tiles fail with a typed message | [CODE] |
| 14 | Battery saver / "restricted" battery | Can delay FLP; onboarding sheet exists and works; "Don't ask again" supported | [RUN/CODE] |
| 15 | Notifications denied (13+) | Banner + deep link exist; alarm audio still plays | [CODE] |
| 16 | Do Not Disturb | Alarm channel `setBypassDnd(true)`; audio uses `USAGE_ALARM` | [CODE] |
| 17 | Bluetooth buds connected | Dual-route speaker + A2DP; rebuilds on connect/disconnect. **Unverified on hardware** | [CODE] |
| 18 | Custom sound file deleted/permission revoked | Probed at fire-time, falls back to default, then to vibration + "alarm fired silently" notification | [CODE] |
| 19 | Two stations fire together | Both ring; screen only dismisses the first (R10) | [CODE] |
| 20 | Unattended alarm | Stops after duration and PAUSES the station (R6) | [CODE] |
| 21 | Accidental volume/back press | Alarm dismissed + PAUSED (R6) | [CODE] |
| 22 | Reinstall / new phone | Auto-backup restores "armed" rows without geofences (P2) | [CODE] |
| 23 | Permission revoked mid-trip | Location callback detects it and stops service; user gets no explanation | [CODE] |
| 24 | Android 8.x | Crash at alarm time (B3) | [BUILD] |
| 25 | Android 15/16 cold start by geofence | Works (geofence is exempt) but first FGS type is wrongly `mediaPlayback` until sync loads | [RUN/CODE] |

---

## 5. Speed/latency model

Straight approach at constant speed from 500 km out, using the app's real constants (gears 10 s/30 s/60 s/5 min/10 min; rings at R+5/30/40/60/100/150/200 km + alert at R; FLP batching up to 2×interval). *Warning* = seconds before reaching the station centre when the alarm fires; negative = missed.

**With geofences working (as shipped), R = 5 km** — min warning (s):

| Delivery model | 100 km/h | 130 | 160 | 200 | 250 | 300 | 350 |
|---|---|---|---|---|---|---|---|
| best (geofence 5 s) | 175 | 133 | 107 | 85 | 67 | 55 | 46 |
| typical (geofence 20 s, batching U(0,T)) | 160 | 118 | 92 | 70 | 52 | 40 | 31 |
| worst (geofence 60 s, batching 2T) | 120 | 78 | 52 | 30 | 12 | 55* | 38* |

\*the model's 300/350 km/h worst case is fired by GPS before the (modelled) late geofence. **R = 3 km at 200+ km/h can be missed entirely in the worst case** — recommend a minimum radius that scales with the train's speed.

**GPS only (geofences failed), R = 5 km, shipped vs proposed (speed-aware gear + batching ≤ 1×):**

| km/h | shipped, typical: missed/60 | proposed, typical | shipped, worst: missed/60 | proposed, worst |
|---|---|---|---|---|
| 100 | 0 | 0 (min 163 s) | 36 | 0 (155 s) |
| 130 | 0 | 0 (117 s) | 60 | 0 (114 s) |
| 160 | 1 | 0 (88 s) | 60 | 0 (88 s) |
| 250 | 19 | 0 (52 s) | 60 | 0 (47 s) |
| 300 | 33 | 0 (38 s) | 60 | 0 (35 s) |

This is a **model**, not a field measurement. It tells you where to look, not what a given phone will do; validate with the field protocol in §7.

---

## 6. English-only cleanup (decision: no per-language support)

Grep results — every reference to the multi-language feature:

| What | Where | Action |
|---|---|---|
| 20 translation folders | `res/values-{bn,de,es,fr,gu,hi,id,it,ja,ko,mr,nl,pl,pt-rBR,pt-rPT,ru,ta,te,tr,vi}/strings.xml` | Delete (after you confirm — they are committed in the 5 unpushed commits; one `git revert`/`git rm` commit keeps history) |
| Language picker UI | `SettingsScreen.kt:149-204` (`languageOptions`, `settings_language_*`, `settings_language_more_coming`) | Remove section |
| Locale persistence | `UserPreferences.kt:59-93, 140` (`KEY_APP_LOCALE`, `LOCALE_SYSTEM`, `appLocaleFlow`, `setAppLocale`) | Remove; DataStore ignores stale keys |
| Startup locale code | `StationAlarmApplication.kt:35-39, 137-154` (`applyPersistedLocale` — a `runBlocking` on main thread), imports `LocaleListCompat`/`AppCompatDelegate` | Remove |
| Manifest | `android:localeConfig`, `AppLocalesMetadataHolderService` service block | Remove |
| `res/xml/locales_config.xml` | whole file | Delete (only referenced by the manifest line above) |
| 24 `settings_language_*` strings | `values/strings.xml` | Remove |
| Tests | `UserPreferencesLocaleTest.kt` (4 tests) | Delete |
| Geocoder hint | `MapSearchViewModel.currentLang()` (`Locale.getDefault().language`, used at lines 114 & 185) | **Decide:** keep (results in the phone's language) or hard-code `"en"` |
| Docs | `PROJECT_STATE.md` Localization section, README | Update |
| Keep | `androidx.appcompat` dependency — still required by `OssLicensesMenuActivity` | — |

Expected win: smaller APK/AAB, ~27 fewer files to maintain, one less startup `runBlocking`, and the Play listing shrinks to one language. Per CLAUDE.md's deletion rule I have **not** deleted anything; this table is the grep evidence to approve against.

---

## 7. The plan to publish

### Phase 0 — Unblock this machine (today)
1. Free space on C: (it's at 0 bytes; builds and the emulator fail). Leave ≥ 20 GB.
2. Push the 5 local commits (or hold them until English-only removal is done — your call).

### Phase 1 — Stop the bleeding: P0 code fixes (≈ 2–3 days)
Order matters because the first three touch crash paths:

| # | Task | Files | Needs approval (locked)? |
|---|---|---|---|
| 1 | B2: Gson/R8 keep rules | `proguard-rules.pro` | No |
| 2 | B1: initial FGS type + try/catch + honest `BootRestoreWorker` result | `LocationService.kt`, `BootRestoreWorker.kt` | **Yes** (LocationService) |
| 3 | B3: API-28 guard (or `minSdk = 28`) | `AlarmAudioController.kt` or `build.gradle.kts` | **Yes** if guard |
| 4 | B5: strip AD_ID perms | `AndroidManifest.xml` | No |
| 5 | B7: OSM attribution | `OsmMapView.kt`/`MapSearchScreen.kt` | No |
| 6 | R3: `MY_PACKAGE_REPLACED` receiver → `BootRestoreWorker` | manifest + `BootReceiver.kt` | No |
| 7 | R7: logs → private storage, rotate, debug-only | `Logger.kt`, `GpsLogger.kt`, `SettingsScreen.kt` | No |

### Phase 2 — Engine correctness (≈ 3–4 days) — all in locked files, I'll prepare diffs for your sign-off
R1 refresh in-memory stations · R2 restart-vs-rearm semantics · R4 graceful geofence fallback + readable errors · R5 speed-aware gearbox and ≤ 1× batching · R6 alarm persistence + hardware keys silence-only · R10 `onNewIntent`, full-screen-intent permission check · P2 wake-lock re-acquire, `setStreamVolume` guard.

### Phase 3 — UX + content (≈ 3 days, parallelisable)
R8 permission flow with proper prominent disclosure · R9 search ranking (done) · English-only cleanup (§6) · real icon, notification glyph, feature graphic, screenshots · Settings/About polish · remove "Debug Logs" from release UI.

### Phase 4 — Tests (start in Phase 1, finish in Phase 3)
Automated (add to repo):
- **Unit:** gearbox function extracted to a pure object (no behaviour change) with table tests from §5; `FixQualityGate`; station search ranking; `requestId` parse; Room migrations 3→6 (schemas 3–6 are committed) using `MigrationTestHelper`.
- **Robolectric:** `StationRepository` state machine (MONITORING→ALERTING→PAUSED, edit, re-arm, reset), `BootRestoreWorker` (service-start failure must not return success), `GeofenceBroadcastReceiver` with fake events.
- **Instrumented smoke on the minified build:** install `release`, assert station search returns "PUNE JN", launch/permission/arm/fire/dismiss. This single test would have caught B2.
- **CI (GitHub Actions):** `testDebugUnitTest`, `lintRelease` (make NewApi fatal), `assembleRelease`, run smoke on a Gradle-managed emulator; fail on any new lint error.

Manual (extend `MANUAL_TEST_PLAN.md`; physical devices — this is where OEMs bite):
- Matrix (minimum): Pixel (stock 15/16), Samsung One UI 6/7, Xiaomi/Redmi HyperOS (largest Indian share), Realme/Oppo ColorOS, OnePlus OxygenOS, one Android 9–11 device, one low-RAM (≤ 3 GB) device.
- Per device: battery "Unrestricted" vs "Optimised"; screen-off 30 min; reboot with armed alarm (far and inside radius); swipe from recents; force-stop; update-in-place install; GPS toggle; airplane mode; BT buds + wired; DND; alarm during phone call; two stations.
- **Field protocol (the one that actually matters):** ride 3 real train journeys (a slow passenger, a 130 km/h express, one overnight > 4 h), screen off, phone in pocket/bag, two phones side by side — one on the shipped build, one on the speed-aware gearbox — export the CSVs (they already contain `gate_decision`, `sats_in_fix`, `flp_failures`…) and measure **minutes of warning**. Target: ≥ 3 min at 130 km/h, no misses in 10 trips.

### Phase 5 — Play Console & compliance (start now; it has the longest lead time)
1. **Developer account.** If it's a *personal* account created after Nov 2023, Play requires a **closed test with ≥ 12 testers opted-in for 14 continuous days** before you may request production access. *(Verify the current numbers in Console; they have changed before.)* Organisation accounts are exempt but need D-U-N-S verification. Start this clock first.
2. **Signing:** create the upload keystore, back it up in two places, use Play App Signing; add `keystore.properties`; produce the AAB with `bundleRelease`.
3. **Privacy policy:** fix and deploy (B4). Link it in Console *and* the app.
4. **Data safety form** (draft — adjust after R7/B5 fixes):
   - Location → **Precise**, collected, *shared* (search bias + reverse-geocode points go to your Worker/LocationIQ), purpose: App functionality, not optional for core feature.
   - Location (background) → used on-device only for alarm; not transmitted.
   - App activity / diagnostics (crash logs, performance), Device or other IDs (Firebase installation ID) → collected, purpose Analytics/App functionality; data encrypted in transit; users can request deletion (give a contact).
5. **Sensitive-permission declarations** (each needs a short video showing the user-visible feature):
   - **Background location** (`ACCESS_BACKGROUND_LOCATION`) — core feature: alarm triggers while the phone is locked/app closed; show the disclosure screen → permission → alarm firing.
   - **Foreground service types** — `location` (continuous tracking to the destination) and `mediaPlayback` (alarm playback after tracking ends). Be ready to defend `mediaPlayback`; if challenged, ring from the location service and drop the second type.
   - **Full-screen intent** — alarm app category; wake screen on lock.
   - **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** — you don't request it (you deep-link to settings), which is the safer choice; keep it that way.
6. **Content rating** questionnaire (no UGC; no ads) · **Target audience** 13+ · **Ads:** none · **App category:** Maps & Navigation or Travel & Local · **Contact email** (the same dedicated address as in the policy).
7. **Target API:** you're at 36 — current as far as I know; 16 KB pages: **pass** ([BUILD]).
8. **Store listing (English only):** title ≤ 30, short ≤ 80, full ≤ 4000 chars; ≥ 2 phone screenshots (aim for 6–8), 512×512 icon, 1024×500 feature graphic. Lead with: "Never miss your stop — an alarm that follows you, not the clock." Be explicit about requirements (location *Allow all the time*, battery exemption) and about not being a guarantee (disclaimer: don't rely solely on it for safety-critical journeys).
9. **Pre-launch report** (Firebase Test Lab runs automatically on upload): fix crashes it finds on Android 8/15 — B1 and B3 will show up here if not fixed first.

### Phase 6 — Rollout
Internal testing (you + 5) → **Closed testing** (12+ real testers, 14 days; ask commuters, include Xiaomi/Samsung users; use Crashlytics + Analytics events `alarm_fired / alarm_dismissed_user / alarm_auto_stopped / ghost_alarm_recovered / gps_watchdog_visible` as KPIs) → Production **staged 5 % → 20 % → 50 % → 100 %**, halting on crash-free < 99.5 % or `alarm_auto_stopped` rate > 15 %. Remote Config already has `min_supported_app_version` and `maintenance_mode` kill-switches — rehearse them once before launch.

### Suggested timeline
| Week | Content |
|---|---|
| 0 | Phase 0, start developer-account/DUNS verification, deploy corrected privacy site |
| 1 | Phase 1 + start tests; first internal build to your own phone(s) |
| 2 | Phase 2 + Phase 3; first field trip with new gearbox |
| 3 | Store assets, declarations and videos; closed test **starts** (14-day clock) |
| 3–5 | Closed test; fix what the field data shows |
| 5–6 | Apply for production, staged rollout |

### Decisions I need from you
1. **Approve edits to locked files** (exact changes: B1, B3 guard, R1, R2, R4, R5, R6, R10, wake-lock). I will not touch them without a yes.
2. `minSdk` 26 or 28?
3. After a reboot *inside* the radius: ring immediately? (my recommendation: yes)
4. Unattended alarm: re-ring / keep ringing, or keep current auto-stop?
5. Hardware volume/back keys: silence-only (recommended) or dismiss?
6. `lang` hint to the geocoder: device language or always `en`?
7. Keep any on-device logging in release (opt-in, private, capped), or debug-only?
8. Personal or organisation developer account? (changes the closed-test requirement)

---

## 8. Fix verification (clone only)

| Fix | Verified how | Result |
|---|---|---|
| B2 Gson/R8 keep rules | Rebuilt release, installed, searched "PUNE" | **Pass** — `PUNE JN` returned; no `STATION_DATA_LOAD_FAILED` in session |
| B1 FGS type (one-line change in `updateForegroundNotification`) | Same reboot test, same armed alarm, same emulator: unpatched build → **2 fatal crashes** (3 reproductions in total across sessions); patched build → **0 crashes**, process alive, `LocationService` `isForeground=true`, `types=0x00000008` (= `FOREGROUND_SERVICE_TYPE_LOCATION`), monitoring notification posted, OS log "Background started FGS: Allowed" | **Pass** |

Not covered by this fix and still open: the `try/catch(ForegroundServiceStartNotAllowedException)` safety net and the `BootRestoreWorker` result honesty (§3 B1) — they are defence-in-depth and I did not build them in the clone.

---

## Appendix A — Environment problems encountered (not app bugs)
C: drive 0 bytes free · 8 GB RAM · emulator hangs under Gradle load (AEHD) · machine slept ≈ 3 h mid-run and dropped the network · ANR dialog ("No response to onStartJob") was emulator CPU starvation (load avg 13–22, class verification 100–500 ms each).

## Appendix B — Artifacts (outside the repo)
`D:\sa_build\` (clone with the two test fixes, signed test APKs, screenshots, build logs, `test-signing.jks` — test key only, never use for Play) · `D:\sa_emu\` (AVD) · simulation: `…\scratchpad\sim_alarm.py`, `sim_alarm2.py`, `sim_out.txt`.
