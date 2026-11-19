package org.wikipedia.page

import kotlinx.coroutines.CompletableDeferred
import org.wikipedia.dataclient.okhttp.OkHttpConnectionFactory
import org.wikipedia.history.HistoryEntry
import org.wikipedia.readinglist.database.ReadingListPage

class PageViewModel {

    var page: Page? = null
    var title: PageTitle? = null
    var pendingHighlightText: String? = null
    var curEntry: HistoryEntry? = null
    var readingListPage: ReadingListPage? = null
    var hasWatchlistExpiry = false
    var isWatched = false
    var forceNetwork = false
    var isReadMoreLoaded = false
    var loadStartElapsedRealtime: Long = 0
    val isInReadingList get() = readingListPage != null

    // Completed by OkHttpWebViewClient once the article's mobile-html response has been served
    // to the WebView, with a value telling whether it came from the OkHttp cache. Used to
    // trigger the background revalidation of cached article HTML.
    private var htmlServedFromCacheDeferred = CompletableDeferred<Boolean>()

    val cacheControl
        get() = when {
            forceNetwork -> OkHttpConnectionFactory.CACHE_CONTROL_FORCE_NETWORK
            // Regular wiki pages (main page, Special:, etc.) always revalidate; article HTML is
            // served stale-while-revalidate (see PageFragmentLoadState.revalidateArticleHtml).
            shouldLoadAsMobileWeb -> OkHttpConnectionFactory.CACHE_CONTROL_NONE
            else -> OkHttpConnectionFactory.CACHE_CONTROL_MAX_STALE
        }

    fun resetHtmlServedSignal() {
        htmlServedFromCacheDeferred = CompletableDeferred()
    }

    fun onHtmlResponseServed(servedFromCache: Boolean) {
        htmlServedFromCacheDeferred.complete(servedFromCache)
    }

    suspend fun awaitHtmlServedFromCache(): Boolean {
        return htmlServedFromCacheDeferred.await()
    }
    val shouldLoadAsMobileWeb get() =
        title?.run { namespace() === Namespace.SPECIAL || isMainPage } ?: run { false } ||
          page?.run { summary.ns !== Namespace.MAIN && summary.ns !== Namespace.USER &&
                  summary.ns !== Namespace.PROJECT && summary.ns !== Namespace.DRAFT || isMainPage } ?: run { false }
}
