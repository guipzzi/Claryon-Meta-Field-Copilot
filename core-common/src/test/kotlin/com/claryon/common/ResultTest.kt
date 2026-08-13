package com.claryon.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTest {

    @Test
    fun success_carries_value_and_maps() {
        val r = Result.success(2).map { it * 3 }
        assertTrue(r.isSuccess)
        assertEquals(6, r.getOrNull())
    }

    @Test
    fun failure_is_preserved_through_map() {
        val err = ClaryonError.Voice("stt.timeout", "STT excedeu o tempo")
        val r: Result<Int> = Result.failure(err)
        val mapped = r.map { it + 1 }
        assertNull(mapped.getOrNull())
        assertTrue(mapped is Result.Failure)
        assertEquals("stt.timeout", (mapped as Result.Failure).error.code)
    }
}
