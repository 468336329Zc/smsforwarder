# SMSForwarder (量子密信短信转发器 v2.0.3-robust)
# 

基于 Android 原生 Kotlin 编写的本机短信监听与量子密信 Webhook 自动转发工具。

## 核心特性

- **100% 纯本机本地存储**：所有机器人配置 (`SharedPreferences`)、短信游标、去重指纹与运行日志 (`files/logs/runtime.log`) 均保存在手机本地，无任何远程服务器或后台管理端。
- **三通道防漏监听架构**：
  1. **静态 + 动态双注册高优先级广播 (`SmsReceiver`)**：按发件人自动聚合拼接多段长短信 PDU。
  2. **通知栏全量监听 (`SmsNotificationService`)**：移除原版硬编码前置词库拦截，支持 `SpannableString` 标题、`EXTRA_BIG_TEXT` 与 `EXTRA_TEXT_LINES` 折叠通知，兼容小米 `com.miui.securitycenter` 及主流国产 ROM。
  3. **本机系统短信库监听 (`SmsContentObserver`) + 4 秒本地兜底轮询**：直接监听本机 `content://sms` 新增记录，防止系统“验证码安全保护”拦截广播。
- **智能短窗口跨通道去重 (`MessageDeduplicator`)**：基于完整短信正文的 SHA-256 指纹进行 10 秒跨通道去重，彻底修复原版 6 位数字正则误杀问题。
- **完整对接量子密信机器人接口 (`QuantumBotClient`)**：支持 `text`（含 `@所有人` / `@指定手机号`）、`image`、`file`、`news`、附件上传接口 (`<=30MB`) 及单机器人 **20 条/分钟** 滑动窗口限流。

## 快速推送到 GitHub 仓库

解压本工程后，在工程根目录执行：

```bash
git push -u origin main
```

推送后，仓库内置的 GitHub Actions (`.github/workflows/build.yml`) 会自动在云端编译并生成可下载的 APK 构建产物。
