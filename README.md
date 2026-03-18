# Mask Android Migration (Native Offline)

This module contains the Android native migration baseline that mirrors the original desktop realtime flow:

- Screen capture via `MediaProjection` (for Tencent Meeting and other video apps)
- FPS throttling (`10/20/30/60`)
- Rolling average over 10-frame window
- Smoothing over latest 5 windows
- Fake threshold `0.3`
- Alert after 6 consecutive fake decisions

## Project entry
- App module: `demo/app`
- Main screen: `demo/app/src/main/java/com/example/myapplication/MainActivity.kt`
- Inference engines:
  - ONNX: `demo/app/src/main/java/com/example/myapplication/inference/OnnxDeepfakeInferenceEngine.kt`
  - Fallback: `demo/app/src/main/java/com/example/myapplication/inference/FallbackHeuristicInferenceEngine.kt`

## Model placement
Put converted model at:

`demo/app/src/main/assets/models/best_model.onnx`

Conversion notes: `demo/docs/model_conversion.md`

Model export tools are provided in:
- `mask/Back_code/model2/export_best_model_onnx.py`
- `mask/Back_code/model2/verify_onnx_consistency.py`

## Quick run
```powershell
Set-Location "D:\Code\Project\Android\demo"
.\gradlew.bat :app:assembleDebug
```

If ONNX model is not present yet, app still runs using fallback heuristic inference so screen-capture + pipeline can be validated.

## Runtime flow
1. Open app and tap `开始检测`
2. Grant screen capture permission
3. Switch to Tencent Meeting (or another video app)
4. Return to app to observe probability, label, inference time, and analyzer FPS

The detection service runs as a foreground service during analysis.

## Deploy to connected emulator/device
```powershell
Set-Location "D:\Code\Project\Android\demo"
.\gradlew.bat :app:installDebug
adb shell am start -n com.example.myapplication/.MainActivity
```

