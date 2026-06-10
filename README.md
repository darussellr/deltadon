# SmartWake

Two-part smart alarm (Samsung Galaxy Watch4 Classic + Android phone) that
learns the relationship between physiological/sleep state at wake time and
subjective morning energy, then times the alarm inside a user-set window to
minimize sleep inertia. Single-user / personal build: sensing runs against the
Samsung Health Sensor SDK with **Health Platform developer mode** enabled on
the watch (no Partner Program needed).

## Modules

| Module | What it is |
|---|---|
| `:shared` | Android library used by both apps: data model, Room schema, `SleepSensingRepository` abstraction, synthetic-night generator, feature extraction, wake strategies, Data Layer wire codec. |
| `:phone` | Phone app (Compose/Material3): TFLite inference service, history + model status UI, training-data export. |
| `:watch` | Wear OS app (Compose for Wear): sensing, alarm scheduling, foreground session service, dismiss + energy logging, local heuristic fallback. |
| `training/` | Python: trains the two-headed Keras model on synthetic nights, exports INT8 TFLite, personalizes the fusion head on exported labels. |

Both apps share `applicationId com.smartwake` (one per device) so the
Wearable Data Layer pairs them.

## How a night works

1. **Start sleep** on the watch → `SleepSessionService` goes foreground
   (health type) and collects coarse bulk signals (~1/min movement + HR).
2. At **window start** an exact alarm ramps the service to the active phase:
   25 Hz accel + PPG batched into 30 s epochs. Each epoch is scored locally
   (`OnlineStageEstimator` → `HeuristicStrategy`) and streamed to the phone.
3. The **phone**, if a TFLite model is installed, scores the epoch and replies
   with a decision; fresh phone verdicts override the local heuristic. No
   phone / no model → the watch decides alone. Either way an exact fallback
   alarm at window end can never be missed.
4. On fire: vibration + full-screen dismiss → energy score (1–10) → optional
   tags. The labeled example (macro features + raw fired epoch) is stored and
   shipped to the phone for the training set.

Usage modes (set on the watch): **passive** (fixed-time alarm, dataset still
collected), **smart window** (model/heuristic decides; epsilon-greedy
exploration near the decision threshold), **research** (stratified random
stage target for fastest label coverage).

## Build & test

```
gradlew :shared:test                              # unit tests incl. end-to-end synthetic pipeline
gradlew :phone:assembleDebug :watch:assembleDebug
```

Requires JDK 17. `local.properties` must point at your Android SDK (Android
Studio generates it).

## Real sensors (Galaxy Watch4+)

Without the Samsung SDK the watch runs **synthetic nights at real-time pace**,
so the entire flow — alarm, dismiss, labeling, phone link — works on an
emulator. For real hardware see `watch/libs/README.md`: drop in
`samsung-health-sensor-api.aar` (the build picks up `watch/src/samsung/`
automatically) and **enable Health Platform developer mode on the watch** —
the one manual setup step that unlocks raw 25 Hz accel/PPG/IBI.

## Model loop

```
training/train_base_model.py            # base model on synthetic nights
adb push smartwake.tflite .../files/models/smartwake.tflite   # see training/README.md
# ...nights accumulate labels...
# phone Model tab → Export training data
training/train_base_model.py --personalize training_examples.json
```

The phone hot-reloads the model file; the cold-start heuristic carries the
first ~1–2 weeks until enough personal labels exist.

## Build order (from the project brief)

1. ~~Scaffold + shared data model + Room~~ ✅
2. ~~Fake repo + synthetic nights → pipeline exercised end-to-end~~ ✅
3. ~~Alarm + foreground service + dismiss/energy logging~~ ✅
4. ~~Feature extraction + heuristic strategy wired into the session service~~ ✅
5. ~~Data Layer watch↔phone link (epochs → decisions, labels → phone)~~ ✅
6. ~~Samsung Health Sensor SDK implementation~~ ✅ *(code complete; compiles in when the AAR is dropped into `watch/libs/` — verify tracker names against your SDK version)*
7. ~~TFLite inference + training/personalization scripts~~ ✅ *(on-device inference done; training runs off-device in Python)*

## Known limitations / next steps

- Samsung repository is written against Sensor SDK 1.3.x API names and has
  not been compiled against a real AAR yet — expect minor fix-ups.
- Bedtime is manual ("Start sleep"); auto-detection is a later milestone.
- Research mode uses one random stage stratum per night; a proper
  stratification ledger (balancing strata across nights) is future work.
- Battery budget for the 25 Hz active window is unmeasured — keep the window
  ≤ 60–90 min initially.
- Phone training is offline (Python); on-device retraining was traded for
  the export → fine-tune → push loop.
