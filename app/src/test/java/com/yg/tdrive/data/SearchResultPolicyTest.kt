package com.yg.tgdrive.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultPolicyTest {
    @Test fun keywordWithFilterIsAppliedBeforePagination() {
        val entries = listOf("海边 1", "山林", "海边 2", "山林 2", "海边 3")
        val result = SearchResultPolicy.page(entries, "海边", limit = 2, offset = 1) { it }
        assertEquals(listOf("海边 2", "海边 3"), result)
    }

    @Test fun blankKeywordKeepsAllEntries() {
        assertEquals(listOf("山林", "海边"), SearchResultPolicy.page(listOf("山林", "海边"), " ", 2, 0) { it })
    }
}
