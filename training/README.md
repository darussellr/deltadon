# Training

`train_base_model.py` builds the two-headed fusion model from the project
brief, trains it, and exports an INT8-quantized TFLite file the phone app
loads at `filesDir/models/smartwake.tflite`.

```
pip install tensorflow numpy

# 1. Base model on synthetic nights (no hardware data needed)
python train_base_model.py

# 2. After 2+ weeks of labeled mornings: export from the phone app's Model
#    tab, pull the JSON, and fine-tune the fusion head only:
adb pull /sdcard/Android/data/com.smartwake/files/training_examples.json
python train_base_model.py --personalize training_examples.json

# 3. Install on the phone (debug build):
adb push smartwake.tflite /sdcard/smartwake.tflite
adb shell run-as com.smartwake mkdir -p files/models
adb shell "run-as com.smartwake sh -c 'cat > files/models/smartwake.tflite' < /sdcard/smartwake.tflite"
```

The phone hot-reloads the model on the next epoch (it checks the file mtime),
and the Model tab shows install state. With no model installed, the phone
stays silent and the watch's heuristic makes all wake decisions — so a bad or
missing model can never strand the alarm.

Cold-start expectation: the heuristic carries the first ~1–2 weeks while
labels accumulate; personalize once the export contains a few dozen examples.
