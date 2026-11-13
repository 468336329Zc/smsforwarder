# SMSForwarder · 量子密信短信转发器

基于 Android 原生 Kotlin 编写的**本机短信监听 + 量子密信机器人 Webhook 自动转发**工具。所有配置与日志只存在手机本地，不经过任何第三方服务器。

## 版本信息

| 项目 | 值 |
|---|---|
| 当前版本 | **v2.0.3-robust** |
| versionCode | 203 |
| 包名 | `com.example.smsforwarder` |
| minSdk / targetSdk | 26 / 34（Android 8.0 及以上） |
| 构建工具 | AGP 8.2.2 + Kotlin 1.9.22 + Gradle 8.4 + JDK 17 |
| 构建产物 | `app-debug.apk`（可直接安装）、`app-release-unsigned.apk`（需自行签名） |

## 本版（v2.0.3-robust）说明

本版的主线是**解决「短信漏转」**，同时补齐界面与运维可用性。

### 1. 三通道防漏监听架构

| 通道 | 实现类 | 作用 | 兜底场景 |
|---|---|---|---|
| 系统短信广播 | `SmsReceiver`（静态 + 动态双注册，`priority=2147483647`） | 收到 `SMS_RECEIVED` / `SMS_DELIVER` 立即触发；同一发件人多段长短信按 PDU 自动聚合拼接 | 常规短信 |
| 通知栏监听 | `SmsNotificationService` | 监听短信应用与 `com.miui.securitycenter` 通知，兼容 `SpannableString` 标题、`EXTRA_BIG_TEXT`、`EXTRA_TEXT_LINES` 折叠通知 | 广播被拦截、ROM 屏蔽后台广播 |
| 短信库监听 | `SmsContentObserver` | 直接监听 `content://sms` 新增记录，附加 **4 秒本地兜底轮询** | 系统「验证码安全保护」完全不发广播 |

三条通道任一命中即可转发，**不再依赖硬编码的「前置词库」放行**——早期版本会按固定词表放过通知，导致验证码类短信被静默丢弃。

### 2. 智能短窗口跨通道去重

`MessageDeduplicator` 以**完整短信正文的 SHA-256 指纹**为键，做 10 秒滑动窗口去重，配合 30 秒过期清理。三通道同时触发同一条短信只转发一次；同时移除了早期用「6 位数字正则」判定去重的逻辑——它会把形如 `106900` 的发件人号码误当成验证码，导致真实验证码短信被吞。

### 3. 完整对接量子密信机器人接口

`QuantumBotClient` 基于 OkHttp 4.12 + Gson，支持 `text`（含 `@所有人` / `@指定手机号`）、`image`、`file`、`news` 与附件上传（≤ 30MB），并内置单机器人 **20 条/分钟**滑动窗口限流，超限自动排队而非直接丢弃。Webhook 强制 `https://`，域名证书走系统信任链（`network_security_config.xml` 禁止明文流量）。

### 4. 纯本机存储与隐私

机器人配置（`SharedPreferences`）、短信游标、去重指纹、运行日志（`files/logs/runtime.log`）全部落在应用私有目录。日志写入前统一经 `SecurityUtils` 脱敏：验证码、手机号、身份证、银行卡号、Webhook 的 `key`/`token` 参数均被掩码，单文件超过 500 行自动裁剪。

## 界面说明

| 页面 | 能力 |
|---|---|
| **主页** | 服务状态卡片（短信权限 / 通知监听 / 机器人总数 / 已启用数 / 启用清单）；快捷操作：测试转发、机器人管理、权限和设置、运行日志；使用提示卡片；右上角菜单（机器人管理 / 关于） |
| **机器人管理** | 机器人列表：启停开关、测试发送、更多操作（编辑 / 测试发送 / 删除）；空列表有引导文案；右下角 FAB 添加 |
| **添加 / 编辑机器人** | 分组表单：基本信息（名称、Webhook 地址）、关键词设置（转发关键词）、号码过滤（监听号码，支持前缀匹配）、状态（启用开关、@ 提醒与指定手机号） |
| **运行日志** | 自检信息（短信权限 / 通知监听 / 电池优化白名单 / 启用机器人数）+ 时间倒序运行日志，等宽字体展示，支持一键清空 |

输入项均支持换行显示，Webhook 长地址与多关键词不会被截断；号码、关键词的示例值写在灰色 hint 中并配说明文字，避免被误认为已填配置。

## 权限与 ROM 适配

- 必需：`RECEIVE_SMS`、`READ_SMS`；Android 13+ 追加 `POST_NOTIFICATIONS`
- 前台服务：`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`，常驻保活
- 特殊权限：通知监听（`BIND_NOTIFICATION_LISTENER_SERVICE`）、电池优化白名单、自启动
- 已适配入口：小米 `com.miui.securitycenter`、华为、OPPO、vivo 的自启动管理页

## 构建与发布

```bash
# 本地构建（需 JDK 17 + Android SDK 34；仓库未内置 gradlew，请用全局 Gradle 8.4）
gradle assembleDebug assembleRelease
```

仓库内置 GitHub Actions（`.github/workflows/build.yml`）：push 到 `main` / `master` 自动执行 `assembleDebug assembleRelease`，产物以 `SMSForwarder-APK` 形式上传。

> `assembleRelease` 产出的是 **unsigned** APK，需配置 `signingConfigs` 才能安装；日常验证请用 debug 包。

## 版本记录

| 版本 | 主要变更 |
|---|---|
| v2.0.3-robust | 三通道冗余监听（广播 / 通知 / 短信库 + 轮询）；SHA-256 跨通道去重替代 6 位数字正则；通知通道移除硬编码前置词库；界面重构（分组表单、图标化快捷操作、关于与版本号、使用提示、列表更多菜单、空态引导）；修复 Kotlin 编译错误导致 CI 构建失败 |
| v2.0.x | 机器人多配置、关键词与号码过滤、运行日志与自检、前台服务保活 |
