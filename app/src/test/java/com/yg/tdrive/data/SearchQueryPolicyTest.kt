package com.yg.tgdrive.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchQueryPolicyTest {

    @Test fun `single chinese character must use substring search`() {
        assertTrue(SearchQueryPolicy.useLike("视"))
    }

    @Test fun `chinese word must use substring search`() {
        assertTrue(SearchQueryPolicy.useLike("我的视频"))
    }

    @Test fun `single latin character must use substring search`() {
        assertTrue(SearchQueryPolicy.useLike("a"))
    }

    @Test fun `latin word keeps fts prefix query`() {
        assertFalse(SearchQueryPolicy.useLike("PREHUB"))
    }

    @Test fun `like pattern wraps and escapes wildcards`() {
        assertEquals("%100\\%\\_x%", SearchQueryPolicy.likePattern("100%_x"))
    }

    @Test fun `single chinese char matches substring of a longer name`() {
        assertTrue(SearchQueryPolicy.matchesLike("我的视频合集", "视"))
    }

    @Test fun `fts query quotes every token as prefix`() {
        assertEquals("\"ab\"* \"cd\"*", SearchQueryPolicy.ftsQuery("ab cd"))
    }
}
