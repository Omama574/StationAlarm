# Proposed changes to behaviour-locked files — NOT APPLIED

`locked-file-fixes.patch` contains the changes proposed in [`../ALARM_ENGINE_TRACE.md` §8](../ALARM_ENGINE_TRACE.md).
It touches files that `CLAUDE.md` freezes against behavioural change (`LocationService`, `AlarmAudioController`,
`ServiceNotifications`, `GeofenceBroadcastReceiver`), so it is **review material, not part of the build**, and has not been applied.

- Generated against the tree that contains the non-locked fixes (the commit that added this directory).
- Verified: `git apply --check` passes, it compiles, and `./gradlew testDebugUnitTest` passes (88 tests) in a scratch clone.
- **Not simulated yet** — see §9 of the trace document for what each run must prove.

To apply after approval:

```bash
git apply docs/proposed-patches/locked-file-fixes.patch
./gradlew testDebugUnitTest
```

Items are described one by one (L1–L12) in the trace document; ask for a subset if you want fewer than all of them.
