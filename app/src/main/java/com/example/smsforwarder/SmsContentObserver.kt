package com.example.smsforwarder

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.example.smsforwarder.util.AppLogger
import com.example.smsforwarder.util.MessageDeduplicator
import com.example.smsforwarder.util.SecurityUtils

class SmsContentObserver(
    private val context: Context,
    private val onSmsCaptured: (sender: String, body: String, timestamp: Long) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        private const val TAG = "SmsContentObserver"
        private const val PREFS_NAME = "sms_observer_state"
        private const val KEY_LAST_ID = "last_sms_id"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    @Volatile
    private var lastHandledId: Long = prefs.getLong(KEY_LAST_ID, -1L)

    fun initBaselineId() {
        if (lastHandledId >= 0L) return
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null,
                null,
                "${Telephony.Sms._ID} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    lastHandledId = cursor.getLong(0)
                    prefs.edit().putLong(KEY_LAST_ID, lastHandledId).apply()
                    AppLogger.d(TAG, "初始化本机短信库游标 _id=$lastHandledId")
                }
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "缺少 READ_SMS 权限，无法读取本机短信库: ${e.message}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "初始化本机短信库游标失败: ${e.message}")
        }
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        scanNewInboxMessages()
    }

    @Synchronized
    fun scanNewInboxMessages() {
        try {
            if (lastHandledId < 0L) {
                initBaselineId()
                if (lastHandledId < 0L) return
            }
            val selection = "${Telephony.Sms._ID} > ?"
            val selectionArgs = arrayOf(lastHandledId.toString())
            val sortOrder = "${Telephony.Sms._ID} ASC"

            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE
                ),
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val sender = cursor.getString(addrIdx) ?: "未知号码"
                    val body = cursor.getString(bodyIdx) ?: ""
                    val date = cursor.getLong(dateIdx)

                    if (id > lastHandledId) {
                        lastHandledId = id
                        prefs.edit().putLong(KEY_LAST_ID, lastHandledId).apply()
                    }
                    if (body.isBlank()) continue

                    AppLogger.d(
                        TAG,
                        "本机短信库捕获新短信(_id=$id) - 发送者: ${SecurityUtils.maskSensitiveContent(sender)}"
                    )
                    if (MessageDeduplicator.isNewMessage(sender, body)) {
                        onSmsCaptured(sender, body, date)
                    }
                }
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "读取本机短信库权限不足: ${e.message}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "扫描本机短信库异常: ${e.message}")
        }
    }
}
