# FieldSpec - Gamma Spectrometer

FieldSpec is a native Android application built for real-time gamma spectroscopy utilizing the Gamma Spectacular GS-PRO-V5 via USB Audio. Geared towards converting a gamma spectrometer into an advanced survey meter.

## Features
- **Pulse Shape Matching**: Dynamic distortion tracking ensuring true signals aren't confused with noise.
- **Adjustable Decay**: Set decay from 0.90 to 1.00 per second with fluid visual refreshing at 20Hz.
- **Fast 5-Second CPM**: Reads live Counts-Per-Minute across a rapid 5-second interval.
- **DataStore Storage**: Configuration parameters like ROI and shapes remain sticky across restarts!

## Installation & Building
1. Open this directory in **Android Studio**. It will automatically detect the `build.gradle.kts` structure and download required wrappers and dependencies.
2. Ensure your phone is enabled for USB Debugging.
3. Select your device and click **Run**.
4. Since it uses standard USB Audio routing, the device simply asks for Microphone permission to bind to the USB data stream.

## Operation & Calibration
1. Plug in the GS-PRO-V5 or similar via USB C cable. Click start to begin recording.
2. Using the `Capture Shape (30s)` button inside **Diagnostics**, you can expose the detector to a constant standard source to record a new perfect template. The app averages 1000 pulses and saves the profile to storage.
3. Drag to select an ROI, tap to reveal bin/energy information
4. Decay speed setting allows to have bins constantly decay their contained counts for a more "on the go" spectrum collection. Not for precise spectrums, but good for quickly identifying materials and using the probe as an advanced survey meter.
