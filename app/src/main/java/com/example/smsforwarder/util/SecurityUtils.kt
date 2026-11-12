package com.example.smsforwarder.util

object SecurityUtils {
    private val codeRegex = Regex("(?<=验证码[是为:：\\s]{0,5})\\d{4,8}|\\d{4,8}(?=是您的验证码)")
    private val phoneRegex = Regex("1[3-9]\\d{9}")
    private val idCardRegex = Regex("\\d{17}[\\dXx]")
    private val bankCardRegex = Regex("\\d{16,19}")
    private val keyParamRegex = Regex("(key=)[^&]*")
    private val tokenParamRegex = Regex("(token=)[^&]*")

    fun maskSensitiveContent(content: String): String {
        return content
            .replace(codeRegex) { "****" }
            .replace(idCardRegex) { it.value.take(4) + "**********" + it.value.takeLast(4) }
            .replace(bankCardRegex) { it.value.take(4) + "********" + it.value.takeLast(4) }
            .replace(phoneRegex) { it.value.take(3) + "****" + it.value.takeLast(4) }
    }

    fun maskWebhookUrl(url: String): String {
        return url
            .replace(keyParamRegex, "$1***")
            .replace(tokenParamRegex, "$1***")
    }
}
