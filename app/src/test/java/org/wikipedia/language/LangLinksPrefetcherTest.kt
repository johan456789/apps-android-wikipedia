package org.wikipedia.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.page.PageTitle

class LangLinksPrefetcherTest {
    @Test
    fun testRoundTrip() {
        val title = PageTitle("Earth", WikiSite.forLanguageCode("en"))
        val langLinks = listOf(
            PageTitle("Erde", WikiSite.forLanguageCode("de")),
            PageTitle("Terre", WikiSite.forLanguageCode("fr"))
        )
        LangLinksPrefetcher.putLangLinks(title, langLinks)
        val cached = LangLinksPrefetcher.getLangLinks(title)!!
        assertEquals(langLinks.size, cached.size)
        assertEquals(langLinks[0].text, cached[0].text)
        assertEquals(langLinks[0].wikiSite.languageCode, cached[0].wikiSite.languageCode)
    }

    @Test
    fun testMutationOfSourceListDoesNotAffectCache() {
        val title = PageTitle("Earth", WikiSite.forLanguageCode("en"))
        val langLinks = mutableListOf(PageTitle("Erde", WikiSite.forLanguageCode("de")))
        LangLinksPrefetcher.putLangLinks(title, langLinks)
        langLinks.clear()
        assertEquals(1, LangLinksPrefetcher.getLangLinks(title)!!.size)
    }

    @Test
    fun testCacheEviction() {
        for (i in 0 until 64) {
            LangLinksPrefetcher.putLangLinks(PageTitle("Title$i", WikiSite.forLanguageCode("en")), emptyList())
        }
        LangLinksPrefetcher.putLangLinks(PageTitle("Title64", WikiSite.forLanguageCode("en")), emptyList())
        assertNull(LangLinksPrefetcher.getLangLinks(PageTitle("Title0", WikiSite.forLanguageCode("en"))))
        assertEquals(0, LangLinksPrefetcher.getLangLinks(PageTitle("Title64", WikiSite.forLanguageCode("en")))!!.size)
    }
}
