# Samsung Health Sensor SDK

Drop `samsung-health-sensor-api.aar` into this folder and rebuild — the real
sensing implementation in `watch/src/samsung/` compiles in automatically
(`watch/build.gradle.kts` checks for the file). Without it the app builds and
runs against the synthetic-night repository.

1. Download the **Samsung Health Sensor SDK** from the Samsung Developers
   portal (developer.samsung.com → Health → Sensor SDK). The AAR is inside the
   SDK zip under `libs/`.
2. Copy it here as `samsung-health-sensor-api.aar`.
3. **On the watch, enable Health Platform developer mode** — this is what
   unlocks raw sensors (25 Hz accel, raw PPG, IBI) without the Partner
   Program for personal use:
   Watch Settings → Apps → Health Platform → tap the title repeatedly until
   developer mode toggles on (procedure can vary by One UI version; see the
   SDK release notes).
4. Reinstall the watch app, grant BODY_SENSORS, and the config screen will
   show `sensors: Samsung SDK`.

Galaxy Watch4 or later is required (this project targets a Watch4 Classic).
The tracker-type names in `SamsungSensingRepository` are written against
SDK 1.3.x — if Samsung renames them in a newer SDK, fix-ups will be flagged
by the compiler the moment the AAR is present.
