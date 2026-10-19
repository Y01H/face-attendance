# 复制为 config/env.ps1。init.ps1 会为本机生成密码与密钥。

# ===== 管理后台登录 =====
# 管理员用户名
$env:ADMIN_USER = 'admin'
# 管理员密码（登录 Web 管理界面使用）
$env:ADMIN_PASSWORD = 'admin12345678'

# ===== 数据加密 =====
# 人脸特征值加密密钥（Base64，用于加密存储的特征向量）
$env:FEATURE_KEY = 'QdojCDISINY0ksxEUdh/ARpfuhEsFSmZnNYnjtRvdtE='
# 本地数据库（H2）密码
$env:LOCAL_DB_PASSWORD = 'A5Gdv+kHEP5kF9yDfRVcRg58+OHnWw/o'

# ===== 设备标识 =====
# 设备唯一编号，上报记录时用于标识来源终端
$env:DEVICE_ID = 'terminal-001'
# 打卡类型（如 PUNCH 上班/通用打卡）
$env:PUNCH_TYPE = 'PUNCH'

# ===== 云端数据库（MySQL） =====
# 是否启用云端同步
$env:CLOUD_ENABLED = 'true'
# 云端数据库连接串（JDBC URL，含编码、时区、SSL 及超时配置）
$env:CLOUD_DB_URL = 'jdbc:mysql://127.0.0.1:3306/face_attendance?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000'
# 云端数据库用户名
$env:CLOUD_DB_USER = 'root'
# 云端数据库密码
$env:CLOUD_DB_PASSWORD = 'root'

# ===== 摄像头采集 =====
# 摄像头索引号（本机第几个摄像头，从 0 开始）
$env:CAMERA_INDEX = '0'
# 采集后端（auto 自动选择，也可指定 dshow 等）
$env:CAMERA_BACKEND = 'auto'

# ===== Web 服务 =====
# 服务监听地址（127.0.0.1 仅本机访问，0.0.0.0 允许外部访问）
$env:SERVER_ADDRESS = '127.0.0.1'
# 服务监听端口
$env:SERVER_PORT = '8080'

# ===== 第三方打卡接口对接 =====
# 是否启用向第三方系统推送打卡记录
$env:THIRD_PARTY_ENABLED = 'false'
# 第三方打卡接口地址
$env:THIRD_PARTY_URL = 'https://your-attendance.example.com/api/punch'
# 第三方接口鉴权 Token
$env:THIRD_PARTY_TOKEN = ''
# 判断成功的响应字段名
$env:THIRD_PARTY_SUCCESS_FIELD = 'success'
# 该字段等于此值时视为推送成功
$env:THIRD_PARTY_SUCCESS_VALUE = 'true'

# ===== 视频预览与性能 =====
# 摄像头采集帧率
$env:CAMERA_FPS = '30'
# 采集画面宽度（像素）
$env:CAMERA_WIDTH = '1920'
# 采集画面高度（像素）
$env:CAMERA_HEIGHT = '1080'
# 预览推流帧率
$env:PREVIEW_FPS = '25'
# JPEG 压缩质量（1-100，越高越清晰但流量越大）
$env:JPEG_QUALITY = '100'
# 视频编码格式（MJPG 解码快、帧率高）
$env:CAMERA_FOURCC = 'MJPG'
# OpenCV 计算线程数
$env:OPENCV_THREADS = '2'
