package org.wikipedia.page

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import org.wikipedia.WikipediaApp
import org.wikipedia.dataclient.RestService
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.okhttp.OkHttpConnectionFactory
import org.wikipedia.util.UriUtil
import org.wikipedia.util.log.L
import java.util.concurrent.ConcurrentHashMap

/**
 * Prefetches article HTML into the OkHttp cache while the link preview is showing, so that the
 * article load that follows is served from cache immediately (stale-while-revalidate).
 *
 * The fetch uses the redirect-resolved title, since that is the exact URL the WebView will
 * request when the user taps "Read article".
 */
object ArticleHtmlPrefetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflightJobs = ConcurrentHashMap<String, Job>()

    fun prefetchAsync(title: PageTitle) {
        if (title.namespace() !== Namespace.MAIN || !WikipediaApp.instance.isOnline) {
            return
        }
        val key = cacheKey(title)
        if (inflightJobs.containsKey(key)) {
            return
        }
        inflightJobs[key] = scope.launch(CoroutineExceptionHandler { _, t -> L.e(t) }) {
            try {
                val request = Request.Builder()
                        .url(ServiceFactory.getRestBasePath(title.wikiSite) +
                                RestService.PAGE_HTML_ENDPOINT + UriUtil.encodeURL(title.prefixedText))
                        // Match the headers the WebView request will send, since the server may
                        // vary the response on Accept-Language (and OkHttp cache lookups compare
                        // vary-relevant headers).
                        .header("Accept-Language", WikipediaApp.instance.getAcceptLanguage(title.wikiSite))
                        .build()
                OkHttpConnectionFactory.client.newCall(request).execute().use { response ->
                    // Drain the body so the response lands in the OkHttp cache.
                    response.body?.bytes()
                    L.d("ArticleHtmlPrefetcher: prefetched '" + title.prefixedText + "' (code " + response.code + ")")
                }
            } catch (t: Throwable) {
                L.e(t)
            } finally {
                inflightJobs.remove(key)
            }
        }
    }

    /** Cancels an in-flight prefetch for the given title, if any. */
    fun cancelInFlight(title: PageTitle) {
        inflightJobs.remove(cacheKey(title))?.cancel()
    }

    internal val inflightJobCount: Int get() = inflightJobs.size

    private fun cacheKey(title: PageTitle): String {
        return "${title.wikiSite.languageCode}:${title.prefixedText}"
    }
}
