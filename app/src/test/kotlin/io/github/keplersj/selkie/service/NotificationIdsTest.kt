package io.github.keplersj.selkie.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationIdsTest {
    @Test
    fun everyNotificationHasItsOwnId() {
        val ids = NotificationIds.ALL
        assertEquals("duplicate notification ids: $ids", ids.size, ids.toSet().size)
    }
}
