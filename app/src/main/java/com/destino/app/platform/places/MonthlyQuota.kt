package com.destino.app.platform.places

import java.time.YearMonth

data class MonthlyQuota(val month: String = "", val used: Int = 0) {
    fun current(now: YearMonth): MonthlyQuota =
        if (month.isBlank() || now > YearMonth.parse(month)) MonthlyQuota(now.toString(), 0) else this

    fun reserve(now: YearMonth): MonthlyQuota? {
        val current = current(now)
        return if (current.used >= LIMIT) null else current.copy(used = current.used + 1)
    }

    companion object { const val LIMIT = 100 }
}
