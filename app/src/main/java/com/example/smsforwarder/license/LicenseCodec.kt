package com.example.smsforwarder.license

/** 校验结果：err 为内部错误码（0 通过），买家文案在激活页映射。 */
data class VerifyResult(
    val err: Int,
    val expireDay: Int = 0,
    val issueDay: Int = 0,
)

/**
 * 授权码解析与校验链（顺序冻结，不得调换）：
 * 格式 → 解码 → 版本 → 公钥验签 → appId → 设备码 → 到期 → 时钟。
 */
object LicenseCodec {

    const val ERR_OK = 0
    const val ERR_FORMAT = 1
    const val ERR_BASE32 = 2
    const val ERR_VERSION = 3
    const val ERR_KEYID = 4
    const val ERR_SIGNATURE = 5
    const val ERR_APPID = 6
    const val ERR_DEVICE = 7
    const val ERR_EXPIRED = 8
    const val ERR_NOT_STARTED = 9
    const val ERR_CLOCK_TAMPER = 10
    const val ERR_STORAGE = 11
    const val ERR_TAMPERED = 12

    fun clean(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }

    fun verify(raw: String, localDevice: ByteArray, today: Int): VerifyResult {
        val s = clean(raw)
        if (s.isEmpty() || s.length != Lic.TEXT_LEN || !s.startsWith(Lic.MAGIC)) {
            return VerifyResult(ERR_FORMAT)
        }
        val bytes = Crockford.decode(s.substring(Lic.MAGIC.length)) ?: return VerifyResult(ERR_BASE32)
        if (bytes.size != Lic.TOTAL_LEN) return VerifyResult(ERR_BASE32)

        val payload = bytes.copyOfRange(0, Lic.PAYLOAD_LEN)
        val sig = bytes.copyOfRange(Lic.PAYLOAD_LEN, Lic.TOTAL_LEN)

        if (payload[0].toInt() and 0xFF != Lic.VERSION) return VerifyResult(ERR_VERSION)

        val pub = Ed25519.hexToBytes(Lic.PUB_KEY_HEX)
        if (!Ed25519.verify(payload, sig, pub)) return VerifyResult(ERR_SIGNATURE)

        val appId = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        if (appId != Lic.APP_ID) return VerifyResult(ERR_APPID)

        val dev = payload.copyOfRange(13, 23)
        if (!dev.contentEquals(localDevice)) return VerifyResult(ERR_DEVICE)

        val issueDay = ((payload[8].toInt() and 0xFF) shl 8) or (payload[9].toInt() and 0xFF)
        val expireDay = ((payload[10].toInt() and 0xFF) shl 8) or (payload[11].toInt() and 0xFF)

        if (expireDay != 0 && expireDay < today) return VerifyResult(ERR_EXPIRED)
        if (issueDay > today) return VerifyResult(ERR_NOT_STARTED)

        return VerifyResult(ERR_OK, expireDay, issueDay)
    }
}
