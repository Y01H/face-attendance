# 1.0.2 视频流畅度更新

旧版页面每 400ms 取一张 JPEG，预览最多约 2.5 FPS；同时在 USB 采集线程做 JPEG 编码，编码慢时影响读帧。

本次改动：

- 使用 MJPEG 长连接持续显示画面，默认预览编码上限 25 FPS。
- USB 采集、JPEG 编码、识别、HTTP 视频发送分开运行；复用采集缓冲区，慢客户端读取最新帧。
- 默认请求 640×480、30 FPS、MJPG；JPEG 质量默认 75。
- OpenCV 默认使用 2 个处理线程，减少模型推理抢占全部 CPU 的情况。
- 页面显示实际采集帧率、编码帧率和识别耗时；隐藏页面暂停预览，会话失效返回登录页。
- 保留此前的登录修复；人脸模板、数据库表和密钥兼容，无需重新登记。

升级：

1. 关闭正在运行的程序。
2. 将修复包中的 `face-attendance.jar` 覆盖到原目录。
3. 保留现有 `config/env.ps1`、`data/` 和 `models/`。
4. 运行 `start.cmd`，浏览器 Ctrl+F5 刷新页面或用无痕窗口重新登录。

默认配置会自动生效。如果现有配置已经显式设置这些变量，以现有值为准；可在实际 `config/env.ps1` 中加入或修改：

```powershell
$env:CAMERA_FPS = '30'
$env:PREVIEW_FPS = '25'
$env:JPEG_QUALITY = '75'
$env:CAMERA_FOURCC = 'MJPG'
$env:OPENCV_THREADS = '2'
```

如果采集 FPS 很低，先关闭 Windows 相机、会议软件等设备占用程序；尝试 `CAMERA_BACKEND='msmf'` 或 `'dshow'`，并以实机支持的分辨率/帧率为准。如果采集正常而编码 FPS 较低，再检查 CPU 占用并尝试将 `OPENCV_THREADS` 改成 1 或将 `PREVIEW_FPS` 改成 20。识别耗时高时可以把 `RECOGNITION_INTERVAL_MS` 改成 500，减少推理频率。

验证：16 项测试通过；真实 HTTP 视频端点在合成原始帧及实际 SFace 并行推理条件下测试约 24.9 FPS，编码约 24.8 FPS，60 张 JPEG 可正确解析。此结果来自 Linux 测试环境，不代表 DS-UVC-E12 在你的 Windows 电脑上必然达到相同帧率。实机请查看页面的帧率指标。
