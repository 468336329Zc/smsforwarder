package com.example.smsforwarder.license

/**
 * 离线授权体系常量（KeyForge 接入侧）。
 * APP_B / appId 0x0102；公钥与管理端签名密钥配对，硬编码，禁止从文件或网络读取。
 */
object Lic {
    const val PUB_KEY_HEX =
        "32DFC4A0AD6315AAFAE825F03AE6CFFD7F6474D3BEBC8DF1140219A66D7F0067"

    /** 本应用标识与 appId（管理端应用卡片分配，不可手改） */
    const val APP_CODE = "APP_B"
    const val APP_ID = 0x0102

    /** 2024-01-01 UTC 的 epoch day */
    const val EPOCH_DAY0 = 19723

    const val MAGIC = "LKY1"
    const val VERSION = 1
    const val PAYLOAD_LEN = 23
    const val SIG_LEN = 64
    const val TOTAL_LEN = PAYLOAD_LEN + SIG_LEN
    const val TEXT_LEN = 144
}
