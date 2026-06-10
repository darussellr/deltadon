#!/usr/bin/env python3
"""SmartWake base-model training + personalization.

Two-headed fusion model (mirrors the Kotlin feature extraction):
  Head A (macro):  5 nightly tabular features -> Dense stack -> 8-dim
  Head B (micro):  750x2 tensor (accel magnitude + raw PPG @ 25 Hz)
                   -> input BatchNorm -> 2x Conv1D+MaxPool -> GRU(16) -> 16-dim
  Fusion: concat(8+16) -> 32 -> 16 + Dropout -> sigmoid scaled to [1, 10]

Base training uses synthetic nights (a numpy port of the watch's
SyntheticNightGenerator), since this is a single-user system with no
population dataset. Personalization fine-tunes only the fusion head on the
labeled examples exported by the phone app.

Usage:
  python train_base_model.py                          # train base, export TFLite
  python train_base_model.py --personalize data.json  # fine-tune fusion head
  adb push smartwake.tflite /data/data/com.smartwake/files/models/smartwake.tflite
  (or via `adb shell run-as com.smartwake` on a debug build)
"""

import argparse
import json
import os

import numpy as np

EPOCH_SAMPLES = 750
MACRO_DIM = 5
STAGES = ["AWAKE", "LIGHT", "DEEP", "REM"]

# Stage profiles matching SyntheticNightGenerator.kt
PROFILES = {
    "AWAKE": dict(accel_sigma=0.08, burst_prob=0.90, burst_amp=0.50, hr=68, hr_sd=3.0, ibi_jitter=25),
    "LIGHT": dict(accel_sigma=0.015, burst_prob=0.12, burst_amp=0.15, hr=58, hr_sd=2.0, ibi_jitter=30),
    "DEEP": dict(accel_sigma=0.006, burst_prob=0.02, burst_amp=0.10, hr=51, hr_sd=1.5, ibi_jitter=45),
    "REM": dict(accel_sigma=0.012, burst_prob=0.10, burst_amp=0.12, hr=64, hr_sd=4.0, ibi_jitter=15),
}

MINUTE = 60_000


def build_hypnogram(rng, time_in_bed_min=480, latency_min=12):
    """90-min cycles: deep front-loaded, REM back-loaded, brief awakenings."""
    segments = []  # (stage, start_min, end_min)
    t = 0

    def add(stage, dur):
        nonlocal t
        if t >= time_in_bed_min or dur <= 0:
            return
        end = min(t + dur, time_in_bed_min)
        segments.append((stage, t, end))
        t = end

    add("AWAKE", latency_min)
    cycle = 0
    while t < time_in_bed_min:
        add("LIGHT", rng.integers(8, 16))
        deep = 38 * (0.55 ** cycle)
        if deep > 4:
            add("DEEP", deep * (1 + rng.uniform(-0.2, 0.2)))
        add("LIGHT", rng.integers(6, 13))
        rem = min(9 + 7 * cycle, 32)
        add("REM", rem * (1 + rng.uniform(-0.25, 0.25)))
        if rng.random() < 0.35:
            add("AWAKE", rng.integers(1, 4))
        cycle += 1
    return segments


def stage_at(segments, minute):
    for stage, start, end in segments:
        if start <= minute < end:
            return stage
    return "AWAKE"


def macro_features(segments, wake_minute):
    """[total_sleep, deep, rem, light, awakening_count], minutes up to wake."""
    totals = {"LIGHT": 0.0, "DEEP": 0.0, "REM": 0.0}
    awakenings = 0
    onset = next((s for s in segments if s[0] != "AWAKE"), None)
    for stage, start, end in segments:
        end = min(end, wake_minute)
        if end <= start:
            continue
        if stage == "AWAKE":
            if onset is not None and start > onset[1]:
                awakenings += 1
        else:
            totals[stage] += end - start
    total = sum(totals.values())
    return np.array([total, totals["DEEP"], totals["REM"], totals["LIGHT"], awakenings],
                    dtype=np.float32)


def synth_epoch(rng, stage):
    """30 s of accel magnitude + PPG at 25 Hz for the given stage."""
    p = PROFILES[stage]
    accel = rng.normal(0, p["accel_sigma"], EPOCH_SAMPLES).astype(np.float32)
    if rng.random() < p["burst_prob"]:
        burst_len = int(25 * rng.uniform(0.8, 3.0))
        start = rng.integers(0, EPOCH_SAMPLES - burst_len)
        accel[start:start + burst_len] += rng.normal(0, p["burst_amp"], burst_len).astype(np.float32)

    hr = p["hr"] + rng.normal(0, p["hr_sd"])
    base_ibi = 60_000.0 / hr
    ibis, covered = [], 0.0
    while covered < 30_000:
        ibi = np.clip(base_ibi + rng.normal(0, p["ibi_jitter"]), 400, 1500)
        ibis.append(ibi)
        covered += ibi

    ppg = np.zeros(EPOCH_SAMPLES, dtype=np.float32)
    beat_idx, beat_start = 0, 0.0
    t_ms = np.arange(EPOCH_SAMPLES) * 40.0
    for i, t in enumerate(t_ms):
        while beat_idx < len(ibis) - 1 and t >= beat_start + ibis[beat_idx]:
            beat_start += ibis[beat_idx]
            beat_idx += 1
        phase = np.clip((t - beat_start) / ibis[beat_idx], 0, 1)
        systolic = np.exp(-((phase - 0.18) ** 2) / 0.0018)
        dicrotic = 0.35 * np.exp(-((phase - 0.48) ** 2) / 0.0098)
        ppg[i] = systolic + dicrotic + 0.05 * rng.normal() + 0.08 * np.sin(2 * np.pi * 0.25 * t / 1000)
    return accel, ppg


def ground_truth_energy(rng, segments, wake_minute):
    """Mirrors SyntheticNightGenerator.groundTruthEnergy (units: 1..10)."""
    stage = stage_at(segments, wake_minute)
    energy = {"AWAKE": 7.6, "LIGHT": 7.2, "REM": 5.6, "DEEP": 3.2}[stage]
    if stage == "DEEP":
        seg = next(s for s in segments if s[0] == "DEEP" and s[1] <= wake_minute < s[2])
        energy -= min((wake_minute - seg[1]) * 0.08, 1.0)
    deep_ends = [s[2] for s in segments if s[0] == "DEEP" and s[2] <= wake_minute]
    if deep_ends:
        energy += min((wake_minute - deep_ends[-1]) / 30.0, 1.0) * 0.7
    slept = macro_features(segments, wake_minute)[0]
    energy += np.clip((slept - 420.0) / 60.0 * 0.5, -1.5, 0.5)
    energy += rng.normal(0, 0.5)
    return float(np.clip(energy, 1.0, 10.0))


def make_dataset(n_nights=400, wakes_per_night=6, seed=7):
    rng = np.random.default_rng(seed)
    macros, micros, labels = [], [], []
    for _ in range(n_nights):
        segments = build_hypnogram(rng)
        night_len = segments[-1][2]
        for _ in range(wakes_per_night):
            wake_minute = rng.uniform(night_len * 0.55, night_len - 1)
            stage = stage_at(segments, wake_minute)
            accel, ppg = synth_epoch(rng, stage)
            macros.append(macro_features(segments, wake_minute))
            micros.append(np.stack([accel, ppg], axis=-1))
            labels.append(ground_truth_energy(rng, segments, wake_minute))
    return (np.array(macros, dtype=np.float32),
            np.array(micros, dtype=np.float32),
            np.array(labels, dtype=np.float32))


def build_model():
    import tensorflow as tf
    from tensorflow.keras import layers

    macro_in = tf.keras.Input(shape=(MACRO_DIM,), name="macro")
    a = layers.Dense(32, activation="relu")(macro_in)
    a = layers.BatchNormalization()(a)
    a = layers.Dropout(0.2)(a)
    a = layers.Dense(16, activation="relu")(a)
    a = layers.Dense(8, activation="relu", name="macro_repr")(a)

    micro_in = tf.keras.Input(shape=(EPOCH_SAMPLES, 2), name="micro")
    b = layers.BatchNormalization()(micro_in)  # reconciles accel/PPG scale mismatch
    b = layers.Conv1D(16, 7, strides=2, activation="relu")(b)
    b = layers.MaxPooling1D(4)(b)
    b = layers.Conv1D(32, 5, activation="relu")(b)
    b = layers.MaxPooling1D(4)(b)
    b = layers.GRU(16, name="micro_repr")(b)  # GRU > LSTM for TFLite latency

    x = layers.concatenate([a, b])
    x = layers.Dense(32, activation="relu", name="fusion_1")(x)
    x = layers.Dense(16, activation="relu", name="fusion_2")(x)
    x = layers.Dropout(0.2)(x)
    raw = layers.Dense(1, activation="sigmoid", name="fusion_out")(x)
    energy = layers.Lambda(lambda v: v * 9.0 + 1.0, name="energy")(raw)  # bound to [1, 10]

    model = tf.keras.Model(inputs=[macro_in, micro_in], outputs=energy)
    model.compile(optimizer="adam", loss="mse", metrics=["mae"])
    return model


def export_tflite(model, micros, macros, out_path):
    import tensorflow as tf

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    def representative():
        for i in range(min(200, len(macros))):
            yield [macros[i:i + 1], micros[i:i + 1]]

    converter.representative_dataset = representative
    # Keep float I/O so the Android side feeds plain float arrays.
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS_INT8,
        tf.lite.OpsSet.TFLITE_BUILTINS,
    ]
    tflite_model = converter.convert()
    with open(out_path, "wb") as f:
        f.write(tflite_model)
    print(f"Wrote {out_path} ({len(tflite_model) / 1024:.0f} KB)")


def load_labeled_examples(path):
    with open(path) as f:
        rows = json.load(f)
    macros, micros, labels = [], [], []
    for row in rows:
        accel = np.array(row["accel"], dtype=np.float32)
        ppg = np.array(row["ppg"], dtype=np.float32)
        if len(accel) != EPOCH_SAMPLES or len(ppg) != EPOCH_SAMPLES:
            continue
        macros.append(np.array(row["macro"], dtype=np.float32))
        micros.append(np.stack([accel, ppg], axis=-1))
        labels.append(float(row["score"]))
    print(f"Loaded {len(labels)} usable labeled examples from {path}")
    return np.array(macros), np.array(micros), np.array(labels, dtype=np.float32)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--personalize", metavar="JSON",
                        help="fine-tune the fusion head on exported labeled examples")
    parser.add_argument("--base-weights", default="smartwake_base.weights.h5")
    parser.add_argument("--out", default="smartwake.tflite")
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--nights", type=int, default=400)
    args = parser.parse_args()

    model = build_model()
    model.summary()

    print("Generating synthetic dataset...")
    macros, micros, labels = make_dataset(n_nights=args.nights)

    if args.personalize:
        if not os.path.exists(args.base_weights):
            raise SystemExit(f"Base weights {args.base_weights} not found — train the base model first.")
        model.load_weights(args.base_weights)
        p_macros, p_micros, p_labels = load_labeled_examples(args.personalize)
        if len(p_labels) < 10:
            raise SystemExit("Need at least 10 labeled examples to personalize.")
        # Freeze both heads; fine-tune only the fusion layers on personal data.
        for layer in model.layers:
            layer.trainable = layer.name.startswith("fusion") or layer.name == "energy"
        model.compile(optimizer="adam", loss="mse", metrics=["mae"])
        model.fit([p_macros, p_micros], p_labels, epochs=args.epochs * 2,
                  batch_size=8, validation_split=0.2)
    else:
        model.fit([macros, micros], labels, epochs=args.epochs,
                  batch_size=32, validation_split=0.15)
        model.save_weights(args.base_weights)
        print(f"Saved base weights to {args.base_weights}")

    export_tflite(model, micros, macros, args.out)


if __name__ == "__main__":
    main()
