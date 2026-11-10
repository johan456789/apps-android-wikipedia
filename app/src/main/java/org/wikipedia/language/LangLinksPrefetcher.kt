package org.wikipedia.language

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.wikipedia.WikipediaApp
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.mwapi.SiteMatrix
import org.wikipedia.page.PageTitle
import org.wikipedia.util.log.L

/**
 * Caches language links (and the site matrix) for articles being read, so that the language
 * list can be displayed immediately when the user opens the language menu, instead of waiting
 * for the network.
 *
 * Prefetching is kicked off in the background every time an article is loaded. Entries are
 * served from this cache only while they are fresh; otherwise the language screen falls back
 * to fetching from the network.
 */
object LangLinksPrefetcher {
    private const val MAX_CACHE_ENTRIES = 64
    private const val LANG_LINKS_TTL_MS = 15 * 60 * 1000L
    private const val SITE_MATRIX_TTL_MS = 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflightKeys = mutableSetOf<String>()
    private val siteMatrixMutex = Mutex()

    private class CachedLangLinks(val langLinks: List<PageTitle>, val timestamp: Long) {
        val isFresh: Boolean get() = System.currentTimeMillis() - timestamp < LANG_LINKS_TTL_MS
    }

    // LRU cache of raw language link entries, keyed by language code and page title.
    private val langLinksCache = object : LinkedHashMap<String, CachedLangLinks>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLangLinks>): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    @Volatile private var cachedSiteInfoList: List<SiteMatrix.SiteInfo>? = null
    @Volatile private var siteMatrixTimestamp = 0L

    /**
     * Fetches the language links for the given title (as well as the site matrix) in the
     * background, populating the cache. Does nothing if the cache is already fresh, or if a
     * request for the same page is already in flight.
     */
    fun prefetchAsync(title: PageTitle) {
        val key = cacheKey(title)
        val cached = synchronized(langLinksCache) { langLinksCache[key] }
        if (cached != null && cached.isFresh) {
            return
        }
        synchronized(inflightKeys) {
            if (!inflightKeys.add(key)) {
                return
            }
        }
        scope.launch(CoroutineExceptionHandler { _, t -> L.e(t) }) {
            try {
                val langLinksDeferred = async {
                    ServiceFactory.get(title.wikiSite).getLangLinks(title.prefixedText).query!!.langLinks()
                }
                val siteInfoDeferred = async { getOrFetchSiteInfoList() }
                val langLinks = langLinksDeferred.await()
                siteInfoDeferred.await()
                synchronized(langLinksCache) {
                    langLinksCache[key] = CachedLangLinks(langLinks, System.currentTimeMillis())
                }
            } catch (t: Throwable) {
                L.e(t)
            } finally {
                synchronized(inflightKeys) { inflightKeys.remove(key) }
            }
        }
    }

    /** Returns the cached language links for the given title, or null if not cached or stale. */
    fun getLangLinks(title: PageTitle): List<PageTitle>? {
        val cached = synchronized(langLinksCache) { langLinksCache[cacheKey(title)] }
        if (cached == null || !cached.isFresh) {
            return null
        }
        return cached.langLinks.map { PageTitle(it.text, it.wikiSite) }
    }

    /** Stores language links fetched elsewhere (e.g. by the language screen) into the cache. */
    fun putLangLinks(title: PageTitle, langLinks: List<PageTitle>) {
        // Keep a defensive copy, since callers may mutate the list they pass in.
        val copy = langLinks.map { PageTitle(it.text, it.wikiSite) }
        synchronized(langLinksCache) {
            langLinksCache[cacheKey(title)] = CachedLangLinks(copy, System.currentTimeMillis())
        }
    }

    /** Returns the cached site matrix info, or null if not cached or stale. */
    fun getSiteInfoList(): List<SiteMatrix.SiteInfo>? {
        if (System.currentTimeMillis() - siteMatrixTimestamp > SITE_MATRIX_TTL_MS) {
            return null
        }
        return cachedSiteInfoList
    }

    fun putSiteInfoList(siteInfoList: List<SiteMatrix.SiteInfo>) {
        cachedSiteInfoList = siteInfoList
        siteMatrixTimestamp = System.currentTimeMillis()
    }

    private suspend fun getOrFetchSiteInfoList(): List<SiteMatrix.SiteInfo> {
        getSiteInfoList()?.let { return it }
        return siteMatrixMutex.withLock {
            getSiteInfoList()?.let { return it }
            val siteInfoList = SiteMatrix.getSites(ServiceFactory.get(WikipediaApp.instance.wikiSite).getSiteMatrix())
            putSiteInfoList(siteInfoList)
            siteInfoList
        }
    }

    private fun cacheKey(title: PageTitle): String {
        return "${title.wikiSite.languageCode}:${title.prefixedText}"
    }
}
