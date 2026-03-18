Place the converted offline model file here as:

best_model.onnx

Expected model contract (current app):
- Input shape: [1, 3, 256, 256]
- Input normalization: (x - mean) / std
  mean = [0.485, 0.456, 0.406]
  std  = [0.229, 0.224, 0.225]
- Output: single logit or single probability-compatible value

If this file is missing, the app falls back to a heuristic engine for demo flow only.

