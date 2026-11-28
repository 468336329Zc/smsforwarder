package com.example.smsforwarder.license

import android.content.Context

/**
 * 本地授权记录：只存 license_text / device_code / expire_day / issue_day / last_day。
 * 启动时用留存的授权码重新走完整校验链，禁止只记 boolean。
 */
object LicenseStore {
    private const val PREF = "license_state"
    private const val KEY_TEXT = "license_text"
    private const val KEY_DEVICE = "device_code"
    private const val KEY_EXPIRE = "expire_day"
    private const val KEY_ISSUE = "issue_day"
    private const val KEY_LAST = "last_day"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun hasLicense(context: Context): Boolean =
        prefs(context).getString(KEY_TEXT, null) != null

    /** 本机当前授权是否有效；无效时清掉损坏记录。 */
    fun isActive(context: Context): Boolean {
        val prefs = prefs(context)
        val text = prefs.getString(KEY_TEXT, null) ?: return false
        val device = DeviceCode.bytes(context)
        val today = LkyDate.today()

        val result = LicenseCodec.verify(text, device, today)
        if (result.err != LicenseCodec.ERR_OK) {
            clear(context)
            return false
        }

        val last = prefs.getInt(KEY_LAST, -1)
        if (last in 1 until today) {
            clear(context)
            return false
        }

        prefs.edit().putInt(KEY_LAST, today).apply()
        return true
    }

    /** 尝试激活；返回内部错误码，0 为成功并落盘。 */
    fun activate(context: Context, raw: String): Int {
        val device = DeviceCode.bytes(context)
        val today = LkyDate.today()
        val result = LicenseCodec.verify(raw, device, today)
        if (result.err != LicenseCodec.ERR_OK) return result.err

        prefs(context).edit()
            .putString(KEY_TEXT, LicenseCodec.clean(raw))
            .putString(KEY_DEVICE, DeviceCode.format(device))
            .putInt(KEY_EXPIRE, result.expireDay)
            .putInt(KEY_ISSUE, result.issueDay)
            .putInt(KEY_LAST, today)
            .apply()
        return LicenseCodec.ERR_OK
    }

    fun expireDay(context: Context): Int = prefs(context).getInt(KEY_EXPIRE, 0)

    /** 成功页展示：永久 / 到期日 */
    fun expireText(context: Context): String {
        val day = expireDay(context)
        return if (day == 0) "永久有效" else LkyDate.text(day)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
