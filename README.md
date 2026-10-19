# Java USB 人脸视频考勤终端（云端 MySQL）

适配目标：海康威视 **DS-UVC-E12 标准 USB/UVC 摄像头**，JDK **1.8**、Maven **3.8.6**、Spring Boot **2.6.6**、MySQL **8.0**。Java 使用 OpenCV 4.9 的 YuNet 检测与 SFace 特征模型，CPU 离线推理，不需要虹软激活码、Python 服务或云端识别 API。发行包包括可执行 JAR、源码和两个 ONNX 模型。

**程序必须安装在插着 USB 摄像头的现场电脑上。** MySQL 可以位于云端；把整个采集程序放在云服务器上，不能直接访问现场电脑的 USB 摄像头。本项目通过标准 UVC 采集，不调用海康网络摄像机 SDK。

## 数据放在哪里

| 数据 | 主存储 | 现场电脑 |
|---|---|---|
| 员工 ID、姓名、启停状态 | 云端 MySQL 的 `fa_employee` | 同步缓存 |
| 人脸特征模板（每人最多 5 个） | 云端 MySQL 的 `fa_face_template` | 加密缓存及内存检索 |
| 原始打卡事件 | 云端 `fa_attendance_event` | 先写本地 H2 持久化日志，再补传 |
| 视频帧、登记照片 | 不持久保存 | 在内存中采集、检测和提取特征 |

默认每 30 秒全量刷新人脸库。云端失联时继续使用最近一次完整缓存；缓存超过 24 小时则暂停识别。员工停用在各终端下一次成功刷新后生效，断网终端可能在缓存有效期内继续识别，按你的管理要求调整 `CACHE_TTL_HOURS`。首次启动没有缓存，需要成功同步云端后才能打卡。全量缓存和线性检索适用于小规模部署，员工很多时应按实测扩展增量同步与索引。

**已有云端数据库不一定能直接复用。** 本项目附带独立的 `fa_` 表，不会读取或修改你未知的旧表。如果已有的是照片，可以通过登记接口重新提取特征；如果已有的是虹软、ArcFace 等特征，不能直接当作 SFace 特征使用，需要原始照片重新登记，或按原有引擎改造。已有表结构、特征模型和存储格式需另行映射。

## Windows x64 部署

1. 安装 JDK 1.8（建议使用较新的 8u 更新版本，需支持 AES-256），设置 `JAVA_HOME`。运行发行 JAR 无需安装 Maven。
2. 解压到例如 `D:\face-attendance`，路径尽量使用英文；摄像头插到此电脑。先在系统相机程序验证画面，再关闭系统相机，以免占用设备。
3. 在云端 MySQL 执行 `sql/cloud-mysql.sql`。已有目标数据库时只执行建表部分；不要直接修改未知的旧人脸表。
4. 双击 `start.cmd`。首次运行只生成 `config/env.ps1`。打开该文件，填写 `CLOUD_DB_URL`、`CLOUD_DB_USER`、`CLOUD_DB_PASSWORD`。
5. 云端数据库配置终端出口 IP 白名单或 VPN 私网，并启用 TLS。默认 URL 使用 `sslMode=VERIFY_IDENTITY`，用证书匹配的数据库域名；私有 CA 需要导入运行 JDK 的信任库。
6. 再次运行 `start.cmd`，打开 **http://127.0.0.1:8080**。用户名默认 `admin`，密码是生成的 `config/env.ps1` 内 `ADMIN_PASSWORD`。
7. 确认“云端模板已连接”，暂停自动打卡，按第三方系统员工唯一 ID 登记每人 3～5 张正面及轻微角度照片；然后启用自动打卡。

`FEATURE_KEY` 是 32 字节随机密钥的 Base64，模板以 AES-256-GCM 加密并绑定模型版本。**同一云端人脸库的所有终端必须使用同一个 FEATURE_KEY**，第二台终端应复制已有密钥，不要使用其新生成的密钥。管理员密码和 `LOCAL_DB_PASSWORD` 可各终端不同。请安全备份配置和本地 `data/`，丢失密钥会导致原模板不能解密。不要把实际配置和 `data/` 提交到代码仓库。

默认 `CAMERA_INDEX=0`。多摄像头电脑可以改为 1、2；Windows 默认 DirectShow，不兼容时把 `CAMERA_BACKEND` 改为 `msmf` 或 `any`。拔掉摄像头后程序每 3 秒尝试重新连接。实际分辨率由相机驱动协商，默认请求 640×480、15 FPS。

## Linux x64 部署

```bash
bash scripts/init.sh
# 编辑 config/env.sh 中的云端连接、设备 ID 等
bash scripts/start.sh
```

使用 JDK 8，授予运行用户 `/dev/videoN` 的读取权限（通常加入 video 组），默认使用 V4L2。可通过 systemd 把 `scripts/start.sh` 设为开机服务，WorkingDirectory 必须是解压目录。程序默认只监听本机；若需要从其他电脑管理，设置 `SERVER_ADDRESS` 并使用带 HTTPS 的反向代理。Mac 和 ARM 设备未作为本次验收目标。

## Maven 构建

```bash
java -version
mvn -version
mvn clean package
# 构建后复制 target/face-attendance.jar 到项目根目录
```

Java 源码兼容 1.8。验证环境使用 JDK 1.8.0_452、Maven 3.8.6。JAR 包含 OpenCV 的平台原生库和 Java 依赖；模型保留为外部文件。源码重新构建第一次需要 Maven 网络下载依赖，运行发行包不需下载模型。

## 第三方考勤联动

没有收到你的第三方系统接口文档，因此默认 `THIRD_PARTY_ENABLED=false`；已实现可配置的 HTTP JSON 通用适配器，不能保证任意厂商直接兼容。

默认请求：

```http
POST https://your-attendance.example.com/api/punch
Authorization: Bearer <配置的 token>
Idempotency-Key: <固定 eventId>
Content-Type: application/json
```

```json
{
  "eventId": "22a37098-6163-4090-b627-a9bb5d441da4",
  "employeeId": "E10001",
  "deviceId": "terminal-001",
  "occurredAt": "2026-10-12T02:00:00Z",
  "punchType": "PUNCH"
}
```

默认要求 HTTP 2xx 且响应 `{"success":true}`。以下环境变量可以映射 JSON 字段，不需要改代码：

| 环境变量 | 默认值 |
|---|---|
| `THIRD_PARTY_EVENT_ID_FIELD` | `eventId` |
| `THIRD_PARTY_EMPLOYEE_ID_FIELD` | `employeeId` |
| `THIRD_PARTY_DEVICE_ID_FIELD` | `deviceId` |
| `THIRD_PARTY_TIME_FIELD` | `occurredAt` |
| `THIRD_PARTY_TYPE_FIELD` | `punchType` |
| `THIRD_PARTY_SUCCESS_FIELD` | `success`（嵌套结构支持 `/data/code`） |
| `THIRD_PARTY_SUCCESS_VALUE` | `true`（如厂商要求成功码 `0` 则改为 `0`） |

如果厂商要求自定义签名、动态 Token 刷新、不同时间格式、额外字段或查询核查接口，需要按接口文档修改 `SyncService.send()`。员工绑定使用第三方员工 ID。默认 `PUNCH_TYPE=PUNCH` 上报原始事件，由第三方计算班次；如果要求上下班标识，终端固定配置为约定值，不能通过人脸判断打卡方向。

所有事件先写本地数据库。云端上传和第三方上传是两条独立的持久化队列；云端按 `event_id` 主键幂等插入。第三方重试保留相同事件 ID 和原始时间，指数退避，最多 12 次后进入待核查状态；多数 HTTP 4xx 立即待核查，408/429 可重试。一条失败不会阻止下一条。**第三方必须实际支持幂等键或事件 ID 去重，才可避免“已接收但响应丢失”造成重复打卡**。仅发送幂等头不能让不支持幂等的服务自动去重。手动重试前先核查第三方记录；没有幂等/查询能力的厂商需另行调整上报策略。

## 识别规则与设置

| 环境变量 | 默认值 | 含义 |
|---|---|---|
| `FACE_THRESHOLD` | 0.50 | SFace 余弦相似度最低值 |
| `FACE_MARGIN` | 0.08 | 第一名与不同员工第二名的最低差值 |
| `FACE_MIN_SIZE` | 80 | 人脸框最小边长，像素 |
| `FACE_MIN_BLUR` | 45 | Laplacian 方差清晰度门槛 |
| `CONFIRM_FRAMES` | 3 | 同一身份且框重叠的连续确认帧数 |
| `RECOGNITION_INTERVAL_MS` | 350 | 识别间隔 |
| `DEDUP_SECONDS` | 60 | 同一员工打卡去重窗口，跨重启生效 |
| `CLOUD_REFRESH_MS` | 30000 | 云端人脸库刷新间隔 |
| `CACHE_TTL_HOURS` | 24 | 离线缓存最大年龄 |

每次只允许一个人在画面内；无脸、多人、模糊、过暗、过曝或明显偏转会被拒绝。默认阈值是起步配置，须使用现场已登记员工、未登记人员及不同光照样本校准。相似度不是身份正确概率。USB RGB 摄像头 + 本项目**没有活体检测**，不能承诺抵抗照片或手机视频代打卡。需要防代打卡时应接入经过验证的活体方案或升级硬件。

## 管理 API

所有接口需登录；写接口需 CSRF Token。浏览器 UI 自动处理。脚本客户端先保存会话并请求 `GET /api/csrf`，然后沿用 Cookie，在请求头携带返回的 header/token。

| 方法 | 路径 | 功能 |
|---|---|---|
| GET | `/api/status` | 摄像头、识别、云端、队列状态 |
| GET | `/api/camera/frame.jpg` | 当前 USB 视频 JPEG 帧 |
| GET | `/api/employees` | 员工及模板数，不暴露特征 |
| POST | `/api/enroll/camera` | JSON employeeId/name，登记当前帧 |
| POST | `/api/enroll/photo` | multipart employeeId/name/file，照片登记 |
| POST | `/api/face/check` | multipart file，照片识别测试，不生成考勤 |
| POST | `/api/gallery/refresh` | 主动刷新云端模板 |
| POST | `/api/recognition/false` / `true` | 暂停 / 启用打卡 |
| DELETE | `/api/employees/{id}` | 停用员工并删除模板 |
| GET | `/api/events` | 最近 100 条本地打卡与同步状态 |
| POST | `/api/events/{eventId}/retry` | 核查后重新发送已失败第三方事件 |

## 验证范围与现场验收

附带测试覆盖：加密解密和防篡改、多帧轨迹确认、本地日志跨重启去重、失败后后续事件继续上传、HTTP 200 业务失败处理、云端快照读取/员工停用/幂等事件、缓存过期、权限和 CSRF、真实模型空白帧推理。

自动化云端测试使用 H2 MySQL 兼容模式，不等于云服务上的 MySQL 8.0 实测。当前运行环境没有你的 DS-UVC-E12 实机、云端数据库地址和第三方接口，不能替你验证 USB 驱动、实际识别准确率、云端 TLS 证书和厂商业务协议。上线前应实测：摄像头索引、登记与识别、陌生人拒绝、多帧及去重、拔插恢复、断网重启补传、员工停用同步、第三方接收与原始时间保存。

## 项目文件

`src/main/java/cn/weishi/attendance/` 包含采集、识别、云端库、本地日志和接口实现；`src/main/resources/static/` 是无需 Node 构建的管理页面；`models/` 包含模型及授权；`sql/` 是云端建表脚本；`config/` 是配置样例；`scripts/` 是启动和初始化脚本。**不要删除 data/ 来处理上传报错，这会丢掉尚未补传的事件。**

模型与依赖官方资料：

- [OpenCV 人脸检测及识别教程](https://docs.opencv.org/4.9.0/d0/dd4/tutorial_dnn_face.html)
- [YuNet 模型与 MIT 授权](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)
- [SFace 模型与 Apache-2.0 授权](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface)
- [MySQL Connector/J TLS 配置](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-security.html)
