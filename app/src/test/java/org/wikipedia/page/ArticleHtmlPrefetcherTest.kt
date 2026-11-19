package org.wikipedia.page

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.wikipedia.dataclient.WikiSite

@RunWith(RobolectricTestRunner::class)
class ArticleHtmlPrefetcherTest {
    @Test
    fun testTalkNamespaceNotPrefetched() {
        val title = PageTitle("Talk:Earth", WikiSite.forLanguageCode("en"))
        ArticleHtmlPrefetcher.prefetchAsync(title)
        assertEquals(0, ArticleHtmlPrefetcher.inflightJobCount)
    }

    @Test
    fun testSpecialNamespaceNotPrefetched() {
        val title = PageTitle("Special:Search", WikiSite.forLanguageCode("en"))
        ArticleHtmlPrefetcher.prefetchAsync(title)
        assertEquals(0, ArticleHtmlPrefetcher.inflightJobCount)
    }
}
