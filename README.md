# IoTControlApp - IoT 设备远程控制 App

> 版本：v1.7 (versionCode=8)
> 平台：Android 7.0 (API 24) 及以上
> 语言：Kotlin
> 通信：阿里云 IoT 平台 MQTT 直连

## 一、项目简介

IoTControlApp 是一款 Android 原生应用，用于通过阿里云 IoT 平台 MQTT 协议远程控制设备。
支持电源开关、灯开关、10 路独立开关、**双路实时温度曲线图表**（Field1_data 蓝色 / Field2_data 绿色，各 1000 点）、设定值下发等功能，
并提供完整的 ACK 状态回读、发送失败自动回滚、自定义报文下发等能力。

## 二、整体架构

```
┌─────────────────────────────────────────────────┐
│               UI 层 (Activities)                │
│  MainActivity   SettingsActivity                │
│  SwitchesActivity  ChartFullscreenActivity      │
│  TemperatureChartView (自绘温度曲线 × 2)       │
└──────────────────┬──────────────────────────────┘
                   │ 调用
┌──────────────────▼──────────────────────────────┐
│          业务核心 AliyunIotManager               │
│  - MQTT 连接管理                                 │
│  - 设备状态集中管理 (DeviceState)                 │
│  - 报文构造 / 解析 / 发送                         │
│  - ACK 超时 / 回滚 / Toast                       │
│  - 温度历史环形缓冲 × 2 (Field1_data / Field2_data) │
│  - SharedPreferences 持久化                      │
└──────────────────┬──────────────────────────────┘
                   │ MQTT
┌──────────────────▼──────────────────────────────┐
│      阿里云 IoT 平台 (Broker:1883)              │
└─────────────────────────────────────────────────┘
```

## 三、模块说明

### 3.1 入口与全局

| 文件 | 职责 |
|---|---|
| [IoTApp.kt](app/src/main/java/com/iot/control/IoTApp.kt) | Application 入口，单例化 `AliyunIotManager`，全 App 共享一个实例 |
| [AndroidManifest.xml](app/src/main/AndroidManifest.xml) | 声明 3 个 Activity + 1 个前台 Service，INTERNET / FOREGROUND_SERVICE 等权限 |

### 3.2 UI 层

| 文件 | 职责 |
|---|---|
| [MainActivity.kt](app/src/main/java/com/iot/control/MainActivity.kt) | 主界面：电源/灯开关、**双路温度曲线图表**（独立折叠/清除/主题色/双击全屏）、设定值、10 路开关入口、收发日志（限 5 条）、接收数据值卡片（Dir 自动修正为 ACK/D>C）、ScrollView 手动滚动（**彻底关闭自动回顶**，滚动到哪停在哪）、自定义报文下发；**曲线折叠时标题中间 info 显示最新温度值（18sp + 主题色），展开时恢复显示点数（13sp + 灰色）** |
| [ChartFullscreenActivity.kt](app/src/main/java/com/iot/control/ChartFullscreenActivity.kt) | **曲线全屏横屏页**：从 MainActivity 双击温度曲线1/2 进入，隐藏系统栏沉浸式全屏，横屏方向 (`sensorLandscape`)，十字线+Tooltip 显示温度与时间戳，onResume 注册历史监听器实现实时刷新；初始化设置 `yAxisTextSize = 39f`（主页默认 26f）、`dragOnlyOnAxis = true`（仅底部时间轴区域 80dp 内允许左右拖动/Fling）、返回按钮 `marginStart = 28dp` |
| [SettingsActivity.kt](app/src/main/java/com/iot/control/SettingsActivity.kt) | 设置页：ProductKey / DeviceName / DeviceSecret 三元组配置、设备号下拉选择 (26001-26010)、连接状态指示器、连接/断开按钮、版本号显示（已移除连接日志卡片） |
| [SwitchesActivity.kt](app/src/main/java/com/iot/control/SwitchesActivity.kt) | 开关控制页：10 路开关独立控制 + 全开/全关 |
| [TemperatureChartView.kt](app/src/main/java/com/iot/control/TemperatureChartView.kt) | **自绘温度折线图**（Canvas 实现，零依赖）：贝塞尔平滑曲线、渐变填充、Y 轴动态缩放、0°C 基线、最新点发光、**双指缩放/单指平移/双击重置**、**setAccentColor 动态主题色**；新增 `Point.timestamp` 时间戳字段、`timeAxisMode`（X 轴显示时间 HH:mm:ss）、`showTooltipOnTouch`（触摸十字线+温度+时间 Tooltip）、`onDoubleTapCallback` 双击回调用于触发全屏、`yAxisTextSize`（外部动态设置 Y 轴标签字号，默认 26f）、`dragOnlyOnAxis`（触摸区域限制：为 true 时仅底部时间轴区域 80dp 内允许左右拖动/Fling，曲线区域拖动只显示 Tooltip 不滚动数据）、`isInAxisZone(y)` 轴区域判断辅助方法 |

### 3.3 通信核心

| 文件 | 职责 |
|---|---|
| [AliyunIotManager.kt](app/src/main/java/com/iot/control/AliyunIotManager.kt) | **核心管理器**（见下方详细说明） |
| [IoTConnectionService.kt](app/src/main/java/com/iot/control/IoTConnectionService.kt) | 前台 Service，保活 MQTT 连接，通知栏常驻 |

### 3.4 AliyunIotManager 核心职责

- **MQTT 连接管理**
  - 阿里云三元组签名认证 (HMAC-SHA1)
  - 防并发连接 (`isConnecting` 标记)
  - 旧客户端安全清理 (`cleanupOldClient`)
  - 自动重连 (`isAutomaticReconnect = true`)
  - 回调身份校验 (忽略旧 client 回调)

- **设备状态集中管理** (`DeviceState`)
  - temperature / power / light / setPoint
  - field1Data / field2Data
  - 10 路开关 BooleanArray
  - 从 SharedPreferences 恢复 / 保存

- **报文构造**
  - `buildStandardPayload()` 实际下发报文
  - `buildDefaultTemplate()` 输入框模板
  - 字段顺序统一：`Field1 → Field1_data → Field2 → Field2_data`

- **ACK 处理**
  - 10 秒超时定时器
  - 成功 → `applyStateFromAck()` 回读设备状态 + 清除快照
  - 失败 → `restoreFromSnapshotAndNotify()` 回滚到发送前状态

- **温度历史环形缓冲（双路独立）**
  - 曲线1：`temperatureHistory` 解析 `Field1_data`，主题色蓝色 `#3B82F6`
  - 曲线2：`temperature2History` 解析 `Field2_data`，主题色绿色 `#10B981`
  - 每路 `CopyOnWriteArrayList` 最多 **1000 个点**，超过自动丢最旧
  - 过滤 0 / NaN / 超范围（-100°C ~ 200°C）异常值
  - `temperatureHistoryListener` / `temperature2HistoryListener` 独立回调通知 UI
  - `addTemperaturePoint()` / `addTemperature2Point()` 公开接口（可扩展模拟注入）
  - `clearTemperatureHistory()` / `clearTemperature2History()` 清除方法

- **Toast 反馈**
  - 成功/失败 Toast，1 秒显示
  - 主线程 Handler 保证 UI 线程

## 四、通信协议

### 4.1 MQTT 连接参数

```
Broker:    tcp://{productKey}.iot-as-mqtt.cn-shanghai.aliyuncs.com:1883
ClientId:  {clientId}|securemode=2,signmethod=hmacsha1,timestamp={ts}|
Username:  {deviceName}&{productKey}
Password:  HMAC-SHA1(content, deviceSecret) 十六进制
content:   "clientId{clientId}deviceName{deviceName}productKey{productKey}timestamp{ts}"
```

### 4.2 Topic

- **下发 (C>D)**: `/{productKey}/{deviceName}/user/update`
- **接收 (D>C)**: `/{productKey}/{deviceName}/user/get`

### 4.3 报文格式

**下发报文**:
```json
{
  "DeviceID": "26001_V0.0.0",
  "Dir": "C>D",
  "power": 0,
  "light": 1,
  "Switches": 0,
  "Field1": 38.00,
  "Field1_data": 0.00,
  "Field2": 0.00,
  "Field2_data": 0.00
}
```

**ACK 报文** (设备端确认):
```json
{
  "DeviceID": "26001_V1.2.0",
  "Dir": "ACK",
  "power": 0,
  "light": 1,
  "Switches": 0,
  "Field1": 38.00,
  "Field1_data": 0.00,
  "Field2": 0.00,
  "Field2_data": 0.00
}
```

### 4.4 字段说明

| 字段 | 类型 | 说明 |
|---|---|---|
| `DeviceID` | string | 设备号，格式 `{前缀}_V{版本}`，版本由 ACK 提供，默认 `V0.0.0` |
| `Dir` | string | `C>D` 控制→设备, `D>C` 设备→控制, `ACK` 确认 |
| `power` | int 0/1 | 电源状态 |
| `light` | int 0/1 | 灯状态 |
| `Switches` | int | 10 路开关位掩码 (bit0=switch1 ... bit9=switch10) |
| `Field1` | float(2位) | 温度值 |
| `Field1_data` | float(2位) | Field1 数据 (由设备端回填) |
| `Field2` | float(2位) | 单路开关时为序号(1~10)，其他为设定值(setPoint) |
| `Field2_data` | float(2位) | Field2 数据 (由设备端回填) |

### 4.5 DeviceID 版本号机制

```
设备 ACK 带 DeviceID="26001_V1.2.0"
    ↓
messageArrived: 前缀匹配 → 保存完整 DeviceID 到 prefs
    ↓
发送时: 从 prefs 读取 device_id
    ├─ 有保存值 → 用保存的版本号 (如 V1.2.0)
    └─ 无保存值 → 用默认 V0.0.0
```

## 五、关键流程

### 5.1 启动流程

```
IoTApp.onCreate → 创建 AliyunIotManager 单例
    ↓
MainActivity.onCreate
    ├─ 恢复电源/灯/设定值/Field_data 到 UI
    ├─ setupListeners() 绑定按钮
    └─ autoConnect()
         ├─ 用户上次主动断开 → 提示需手动连接
         └─ 未主动断开 → 自动连接设备
```

### 5.2 发送命令流程

```
用户点击按钮
    ↓
savePreSendSnapshot() 保存发送前状态
    ↓
乐观更新 deviceState + UI
    ↓
buildStandardPayload() 构造报文
    ↓
Mqtt publish + 10 秒超时定时器
    ├─ 收到 ACK → applyStateFromAck() 回读状态 + clearPreSendSnapshot()
    └─ 超时 → restoreFromSnapshotAndNotify() 回滚 + 持久化 + 刷新 UI
```

### 5.3 接收报文流程

```
messageArrived(topic, payload)
    ↓
去重检查 (lastTopic / lastPayload)
    ↓
判断 Dir:
    ├─ "ACK" → applyStateFromAck() + ackListener + Toast
    └─ "D>C" → messageListener + handleCustomMessage() 解析参数
```

## 六、温度曲线操作指南

### 6.1 主页曲线卡片

| 操作 | 效果 |
|------|------|
| **双击曲线** | 进入全屏横屏模式 |
| **单指左右拖动** | 平移查看历史区间 |
| **双指捏合** | 缩放时间范围（捏开放大、捏合缩小） |
| **双击空白处** | 重置为自动跟随最新点 |
| **点击箭头 `›`** | 折叠/展开曲线卡片；**折叠时标题中间 info 显示最新温度值（18sp + 主题色），展开时恢复显示点数（13sp + 灰色）** |
| **清除按钮** | 清空所有历史点 |

### 6.2 全屏横屏页

| 操作 | 效果 |
|------|------|
| **双指捏开** | 放大（减少可视点数，曲线更精细） |
| **双指捏合** | 缩小（增加可视点数，看更长时间跨度） |
| **曲线区域单指拖动** | ❌ 不滚动数据，仅显示十字线 + 悬浮气泡（温度值 + HH:mm:ss 时间戳） |
| **底部时间轴区域（Y > height-80dp）单指拖动** | ✅ 左右平移查看更早的历史数据 |
| **曲线区域单指 Fling** | ❌ 不动 |
| **底部时间轴区域单指 Fling** | ✅ 惯性滑动平移 |
| **双击空白处** | 重置为自动跟随最新点（曲线实时右移） |
| **左上角半透明圆按钮**（marginStart=28dp） | 退出全屏返回主页 |
| **系统返回键** | 退出全屏返回主页 |

### 6.3 全屏页特性

- **沉浸式全屏**：隐藏状态栏和导航栏，最大化图表可视面积
- **横屏自动旋转**：`sensorLandscape`，设备横握自动进入最佳视角
- **实时刷新**：进入全屏后自动注册温度历史监听器，新数据持续追加
- **时间轴模式**：X 轴显示真实时间戳 `HH:mm:ss`（主页显示序号）
- **退出自动解绑**：`onPause` 时释放 listener，不影响主页曲线更新

## 七、状态持久化

所有设备状态通过 `SharedPreferences("iot_config")` 持久化：

| Key | 类型 | 内容 |
|---|---|---|
| `product_key` | String | 阿里云 ProductKey |
| `device_name` | String | 阿里云 DeviceName |
| `device_secret` | String | 阿里云 DeviceSecret |
| `device_id` | String | 设备号含版本 (如 `26001_V1.2.0`) |
| `power_on` | Boolean | 电源开关状态 |
| `light_on` | Boolean | 灯开关状态 |
| `set_point` | Float | 设定值 |
| `switch_states` | String | 10 路开关状态 "1,0,1,0,..." |
| `manual_disconnected` | Boolean | 是否主动断开 |

## 八、构建与运行

### 8.1 环境要求

- Android Studio Hedgehog 或更高
- JDK 17
- Android SDK 34 (compileSdk)
- Gradle 8.14.5

### 8.2 当前机器工具路径（Windows）

| 工具 | 路径 |
|---|---|
| **Android Studio** | `C:\Program Files\Android\Android Studio` |
| **JDK (JBR)** | `C:\Program Files\Android\Android Studio\jbr` |
| **Android SDK** | `C:\Users\Administrator\AppData\Local\Android\Sdk` |
| **ADB** | `C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| **Emulator** | `C:\Users\Administrator\AppData\Local\Android\Sdk\emulator\emulator.exe` |
| **local.properties** | `sdk.dir=C:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk` |

### 8.3 构建

**Linux / macOS：**
```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug
```

**Windows PowerShell：**
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

APK 输出位置：
```
app\build\outputs\apk\debug\IoTControlApp_v1.5_(6)_debug.apk
app\build\outputs\apk\release\...
```

### 8.4 模拟器调试（Windows PowerShell）

```powershell
$env:ANDROID_HOME = "C:\Users\Administrator\AppData\Local\Android\Sdk"

# 列出可用模拟器
& "$env:ANDROID_HOME\emulator\emulator.exe" -list-avds

# 启动模拟器（例：Pixel_7）
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd Pixel_7 -no-snapshot-load -no-boot-anim

# 等待设备就绪
& "$env:ANDROID_HOME\platform-tools\adb.exe" wait-for-device

# 安装 APK
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r "path\to\app-debug.apk"

# 启动 App
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n com.iot.control/.MainActivity
```

### 8.5 运行（真机）

1. 开启 USB 调试，用数据线连接手机
2. 安装 APK：`adb install -r app-debug.apk`
3. 打开 App → 点击设置按钮
4. 填写阿里云三元组 (ProductKey / DeviceName / DeviceSecret)
5. 选择设备号 (26001-26010)
6. 返回主页自动连接
7. 控制设备 / 查看收发日志

## 九、目录结构

```
IoTControlApp/
├── app/
│   ├── build.gradle                    # 模块构建配置
│   ├── proguard-rules.pro
│   ├── src/main/
│   │   ├── AndroidManifest.xml        # 清单文件
│   │   ├── java/com/iot/control/
│   │   │   ├── IoTApp.kt              # Application 入口
│   │   │   ├── MainActivity.kt        # 主界面（含温度曲线）
│   │   │   ├── ChartFullscreenActivity.kt # 曲线全屏横屏页 ★
│   │   │   ├── SettingsActivity.kt    # 设置页
│   │   │   ├── SwitchesActivity.kt    # 开关控制页
│   │   │   ├── TemperatureChartView.kt# 自绘温度曲线图表 ★
│   │   │   ├── AliyunIotManager.kt    # MQTT 通信核心
│   │   │   └── IoTConnectionService.kt# 前台保活服务
│   │   └── res/
│   │       ├── drawable/              # 背景图标资源（含 bg_back_btn.xml）
│   │       ├── layout/                # 布局文件（含 activity_chart_fullscreen.xml）
│   │       ├── mipmap/                # 启动图标
│   │       └── values/                # colors/strings/themes
├── build.gradle                        # 项目构建配置
├── settings.gradle
├── gradle/wrapper/                     # Gradle Wrapper
├── gradlew / gradlew.bat
└── README.md                           # 本文件
```

## 十、版本历史

| 版本 | versionCode | 主要内容 |
|---|---|---|
| v1.0 | 1 | 初始版本：基础 MQTT 控制、ACK 回读、开关管理 |
| v1.1 | 2 | 修复开机反复断连、发送失败回滚、Field1_data/Field2_data 解析、DeviceID 版本动态获取、报文字段顺序统一、Toast 1秒、设备号选项 26001-26010 |
| v1.2 | 3 | **温度曲线图表**：删除原温度旋钮调节卡片 → 自绘 Canvas 折线图，数据源 Field1_data，环形缓冲 50 点，Y 轴动态缩放，贝塞尔平滑曲线+渐变填充+0°C 基线+最新点发光；异常温度值保护（-100°C ~ 200°C） |
| v1.3 | 4 | **双路温度曲线**：复制温度曲线卡片创建曲线2（数据源 Field2_data，主题色绿色 `#10B981`）；环形缓冲扩容至 **1000 点**；新增手势交互（双指缩放/单指平移/双击重置）；曲线卡片支持折叠（箭头 `›` 与其他卡片统一）；**收发日志限 5 条**（ScrollView 380dp）；ScrollView 滑底自动 smoothScroll 回顶（仅用户触摸触发，程序更新不影响）；接收卡片 Dir 自动修正为 ACK/D>C；删除设定值卡片下方 Field1_data/Field2_data 显示；TemperatureChartView 新增 `setAccentColor()` 动态主题色接口 |
| v1.4 | 5 | **温度曲线双击全屏横屏**：新增 `ChartFullscreenActivity`（AndroidManifest 配置 `sensorLandscape`）从 MainActivity 双击温度曲线进入；TemperatureChartView `Point` 新增 `timestamp` 字段（`addPoint()` 自动记录），新增 `timeAxisMode`（X 轴显示 HH:mm:ss）、`showTooltipOnTouch`（触摸十字线+温度+时间悬浮气泡）、`onDoubleTapCallback` 双击回调接口；全屏页 onResume 注册温度历史 listener 实现实时追加新点，onPause 释放避免干扰 MainActivity；全屏页半透明圆形返回按钮 `bg_back_btn.xml` + 系统返回键双退出；WindowInsetsControllerCompat 隐藏系统栏沉浸式全屏（保留 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE） |
| v1.5 | 6 | **删除设置页连接日志卡片**；新增第六章"温度曲线操作指南"（主页+全屏手势对照表、全屏页特性说明） |
| v1.6 | 7 | **全屏页改进**：① `TemperatureChartView` 新增公开属性 `yAxisTextSize`（默认 26f），`ChartFullscreenActivity` 初始化时设为 39f（全屏 Y 轴字体比主页大 13f）；② 全屏返回按钮 `marginStart` 从隐含 16dp 改为 28dp（右移 12dp）；③ 主页 ScrollView **彻底关闭自动回顶**（`setupScrollAutoTop()` 禁用，滚动到哪停在哪）；④ 曲线折叠/展开标题中间 info 智能显示：折叠时显示最新温度（18sp + 主题色蓝/绿），展开时恢复点数（13sp + 灰色） |
| v1.7 | 8 | **全屏触摸区域限制**：`TemperatureChartView` 新增 `dragOnlyOnAxis` 开关（默认 false）和 `isInAxisZone(y)` 辅助方法；当 `dragOnlyOnAxis=true`（全屏页启用）时：曲线区域（Y < height-80dp）单指拖动/Fling 不滚动数据、仅显示十字线 Tooltip；仅底部时间轴区域（Y ≥ height-80dp）才允许左右平移和惯性滑动；双指缩放不受区域限制始终可用 |

## 十一、下载

- **APK 直链**: [app-debug.apk](app/build/outputs/apk/debug/app-debug.apk)
- **完整备份 zip**: [IoTControlApp-backup.zip](IoTControlApp-backup.zip)
- **GitHub 仓库**: https://github.com/htubuad/sjkz_app