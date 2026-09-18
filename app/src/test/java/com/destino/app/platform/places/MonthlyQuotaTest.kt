package com.destino.app.platform.places

import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class MonthlyQuotaTest {
    @Test fun permitsExactlyOneHundredRequests() {
        val month = YearMonth.of(2026, 9)
        var quota = MonthlyQuota()
        repeat(100) { quota = requireNotNull(quota.reserve(month)) }
        assertEquals(100, quota.used)
        assertNull(quota.reserve(month))
    }

    @Test fun renewsInNextMonthIncludingYearBoundary() {
        val exhausted = MonthlyQuota("2026-12", 100)
        assertEquals(MonthlyQuota("2027-01", 1), exhausted.reserve(YearMonth.of(2027, 1)))
    }

    @Test fun movingClockBackDoesNotRenewAllowance() {
        assertNull(MonthlyQuota("2026-09", 100).reserve(YearMonth.of(2026, 8)))
    }

    @Test fun persistedCountContinuesInSameMonth() {
        assertEquals(79, MonthlyQuota("2026-09", 78).reserve(YearMonth.of(2026, 9))?.used)
    }
}
