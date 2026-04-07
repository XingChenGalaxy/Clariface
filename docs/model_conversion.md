# best_model.pth → Android ONNX 转换指南

## 目标
将 `mask/Back_code/weight_save/best_model.pth` 转换为
`Clariface-main/app/src/main/assets/models/best_model.onnx`，
供 Clariface-main Android 项目进行完全离线推理。

## 项目结构说明

```
masksen/
├── mask/                          ← uni-app 移动端（暂不使用）
│   └── Back_code/
│       ├── model2/
│       │   ├── model.py           ← 模型定义（EfficientNetDetector）
│       │   ├── export_onnx.py     ← ONNX 导出脚本 ✅
│       │   └── verify_onnx.py     ← 一致性验证脚本 ✅
│       └── weight_save/
│           └── best_model.pth     ← 训练权重
├── maskora/                       ← PC 端参考实现
└── Clariface-main/                ← Android 主项目 ✅
    └── app/src/main/
        ├── assets/models/
        │   └── best_model.onnx    ← 导出产物（放这里）
        └── java/.../inference/
            └── OnnxDeepfakeInferenceEngine.kt
```

## Android 端推理契约

| 项目 | 值 |
|------|----|
| 运行时 | ONNX Runtime Android 1.20.0 |
| 输入名称 | `input` |
| 输入形状 | `[1, 3, 256, 256]` float32 |
| 输入预处理 | ImageNet 归一化：mean=[0.485,0.456,0.406]，std=[0.229,0.224,0.225] |
| 输出 | 单个 logit（标量）；App 内部 sigmoid → probability |
| probability > 0.3 | label=1（真实） |
| probability ≤ 0.3 | label=0（疑似伪造） |

## 重要兼容性说明

`model.py` 中的 `DCTLayer` 使用了 `torch_dct.dct_2d`，
该算子**不支持直接导出到 ONNX**。

`export_onnx.py` 的解决方案：在导出时自动将 `DCTLayer` 替换为
纯矩阵乘法实现的 `OnnxCompatDCTLayer`（数值等价，误差 < 1e-4），
无需修改原始模型代码，也无需重新训练。

## 环境准备

```powershell
pip install torch torchvision torch_dct onnxruntime
```

> 如果只导出不验证，可以不安装 `onnxruntime`（脚本会自动跳过验证步骤）。

## 导出步骤

**第一步：导出 ONNX 模型**

在 `e:\masksen` 根目录下执行：

```powershell
cd e:\masksen
python mask/Back_code/model2/export_onnx.py
```

脚本会自动：
1. 加载 `mask/Back_code/weight_save/best_model.pth`
2. 将 DCTLayer 替换为 ONNX 兼容实现
3. 导出到 `Clariface-main/app/src/main/assets/models/best_model.onnx`
4. 进行快速数值验证（需要 onnxruntime）

**第二步（可选）：详细一致性验证**

```powershell
cd e:\masksen
python mask/Back_code/model2/verify_onnx.py --samples 16 --max-diff 1e-3
```

**第三步：编译并安装 Android 应用**

```powershell
cd e:\masksen\Clariface-main
.\gradlew.bat :app:installDebug
```

## 全流程工作原理

```
屏幕画面
    │
    ▼  ScreenCaptureService（MediaProjection）
每帧 Bitmap
    │
    ▼  FaceRoiCropper（OpenCV Haar Cascade）
人脸 ROI Bitmap（256×256）
    │
    ▼  OnnxDeepfakeInferenceEngine（ONNX Runtime）
      preprocess: resize→归一化→NCHW FloatBuffer
      infer: OrtSession.run → logit → sigmoid
    │
    ▼  probability（float）
    │
    ▼  RealtimeDecisionEngine
      滑动窗口平均（最近 5 次）
      连续 6 次 smoothed ≤ 0.3 → isAlert=true
    │
    ▼  UI 广播 / 悬浮窗更新
```

## 验证清单

- [ ] `export_onnx.py` 输出"导出完成"
- [ ] `verify_onnx.py` 所有样本通过（绝对误差 < 1e-3）
- [ ] `best_model.onnx` 已出现在 `Clariface-main/app/src/main/assets/models/`
- [ ] Android App 启动时日志无 ONNX 加载异常（即不走 FallbackHeuristicInferenceEngine）
- [ ] 对着人脸画面：probability 稳定在某个值，ROI 状态显示"已检测到人脸"
- [ ] 伪造视频画面：probability ≤ 0.3 时触发"疑似伪造"告警
