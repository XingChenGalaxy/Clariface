# best_model.pth -> Android ONNX conversion guide

## Goal
Convert `mask/Back_code/weight_save/best_model.pth` to `demo/app/src/main/assets/models/best_model.onnx` for fully offline inference on Android.

## Current app-side inference contract
- Runtime: ONNX Runtime Android
- Input: `float32` tensor in NCHW `[1, 3, 256, 256]`
- Preprocess:
  - RGB order
  - resize to `256x256`
  - normalize with ImageNet stats:
    - mean: `[0.485, 0.456, 0.406]`
    - std: `[0.229, 0.224, 0.225]`
- Output: one scalar (logit preferred); app applies sigmoid

## Important compatibility note
`mask/Back_code/model2/model.py` uses `torch_dct.dct_2d` and custom branches.
This op may block direct ONNX export. Typical strategies:

1. Replace `DCTLayer` with ONNX-supported approximation (recommended)
2. Keep architecture but register custom op (hard on Android)
3. Distill/retrain a mobile-friendly model for ONNX/TFLite (long-term)

## Scripts in this repository
- Export script: `mask/Back_code/model2/export_best_model_onnx.py`
- Consistency script: `mask/Back_code/model2/verify_onnx_consistency.py`

## Suggested conversion steps (Python)
1. Run export script to generate `best_model.onnx` directly into Android assets.
2. Run consistency script to compare PyTorch vs ONNX outputs.
3. Rebuild Android app and verify app uses ONNX engine.

### Export command
```powershell
Set-Location "D:\Code\Project\Android"
python -u .\mask\Back_code\model2\export_best_model_onnx.py
```

### Consistency command
```powershell
Set-Location "D:\Code\Project\Android"
python -u .\mask\Back_code\model2\verify_onnx_consistency.py --samples 8 --max-diff-threshold 1e-3
```

### Minimal export skeleton
```python
import torch
from model import EfficientNetDetector

ckpt = torch.load("best_model.pth", map_location="cpu")
state = ckpt.get("model_state", ckpt)
model = EfficientNetDetector(pretrained=False)
model.load_state_dict(state)
model.eval()

dummy = torch.randn(1, 3, 256, 256)

torch.onnx.export(
    model,
    dummy,
    "best_model.onnx",
    input_names=["input"],
    output_names=["logit"],
    opset_version=17,
    do_constant_folding=True,
)
```

## Verification checklist
- [ ] ONNX model loads in Netron (or ORT desktop)
- [ ] Android app starts with ONNX engine (no fallback)
- [ ] On fixed test frames, PyTorch vs ONNX abs diff is acceptable
- [ ] End-to-end FPS matches your target profile on target device

## Deploy
After model export passes verification, deploy to emulator/device:

```powershell
Set-Location "D:\Code\Project\Android\demo"
.\gradlew.bat :app:installDebug
adb shell am start -n com.example.myapplication/.MainActivity
```

