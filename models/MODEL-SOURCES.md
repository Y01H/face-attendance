# 模型来源与校验

两个模型来自 OpenCV Zoo 官方仓库。本项目固定使用以下版本，不会运行时下载或自动换模型。

| 文件 | 大小（字节） | SHA-256 |
|---|---:|---|
| face_detection_yunet_2023mar.onnx | 232589 | 8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4 |
| face_recognition_sface_2021dec.onnx | 38696353 | 0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79 |

来源：

- https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx
- https://github.com/opencv/opencv_zoo/blob/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx

对应授权全文保留在 `licenses/YuNet-LICENSE`（MIT）和 `licenses/SFace-LICENSE`（Apache-2.0）。Java/OpenCV 依赖的许可及通知随 Maven JAR 保留。替换识别模型后原有特征无法保证兼容，需要重新提取并使用新的模型版本标记。
