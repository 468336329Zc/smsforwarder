package com.example.smsforwarder.license

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 授权日期：epoch 2024-01-01 UTC 起算的天数。 */
object LkyDate {
    private val F = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun today(): Int = LocalDate.now().toEpochDay().toInt() - Lic.EPOCH_DAY0

    fun text(day: Int): String =
        LocalDate.ofEpochDay(Lic.EPOCH_DAY0.toLong() + day).format(F)
}
