package com.programmersbox.scrcpyviewer.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class BucketsTest {
    @Test fun bucketLabels() {
        assertEquals("<1", bucket(0, listOf(1, 5)))
        assertEquals("1-5", bucket(3, listOf(1, 5)))
        assertEquals(">=5", bucket(9, listOf(1, 5)))
    }

    @Test fun edgeIsLowerInclusive() {
        assertEquals("1-5", bucket(1, listOf(1, 5)))
        assertEquals(">=5", bucket(5, listOf(1, 5)))
    }

    @Test fun middleBucketOfMinuteEdges() = assertEquals("5-15", bucket(10, MINUTE_EDGES))
}
