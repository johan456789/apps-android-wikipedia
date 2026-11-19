package org.wikipedia.page

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.analytics.eventplatform.ArticleLinkPreviewInteractionEvent
import org.wikipedia.auth.AccountUtil
import org.wikipedia.bridge.CommunicationBridge
import org.wikipedia.bridge.JavaScriptActionHandler
import org.wikipedia.categories.db.Category
import org.wikipedia.database.AppDatabase
import org.wikipedia.dataclient.RestService
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.mwapi.MwNotLoggedInException
import org.wikipedia.dataclient.mwapi.MwQueryResponse
import org.wikipedia.dataclient.okhttp.OfflineCacheInterceptor
import org.wikipedia.dataclient.okhttp.OkHttpConnectionFactory
import org.wikipedia.dataclient.page.PageSummary
import org.wikipedia.history.HistoryEntry
import org.wikipedia.notifications.AnonymousNotificationHelper
import org.wikipedia.page.leadimages.LeadImagesHandler
import org.wikipedia.page.tabs.Tab
import org.wikipedia.settings.Prefs
import org.wikipedia.staticdata.UserTalkAliasData
import org.wikipedia.util.DateUtil
import org.wikipedia.util.UriUtil
import org.wikipedia.util.log.L
import org.wikipedia.views.ObservableWebView
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PageFragmentLoadState(private var model: PageViewModel,
                            private var fragment: PageFragment,
                            private var webView: ObservableWebView,
                            private var bridge: CommunicationBridge,
                            private var leadImagesHandler: LeadImagesHandler,
                            private var currentTab: Tab) {
    private var pageLoadJob: Job? = null

    private val htmlServedTimeoutMs = 20_000L

    fun load(pushBackStack: Boolean) {
        if (pushBackStack && model.title != null && model.curEntry != null) {
            // update the topmost entry in the backstack, before we start overwriting things.
            updateCurrentBackStackItem()
            currentTab.pushBackStackItem(PageBackStackItem(model.title!!, model.curEntry!!))
        }
        pageLoad()
    }

    fun loadFromBackStack() {
        if (currentTab.backStack.isEmpty()) {
            return
        }
        val item = currentTab.backStack[currentTab.backStackPosition]
        // display the page based on the backstack item, stage the scrollY position based on
        // the backstack item.
        fragment.loadPage(item.title, item.historyEntry, false, item.scrollY)
        L.d("Loaded page " + item.title.displayText + " from backstack")
    }

    fun updateCurrentBackStackItem() {
        if (currentTab.backStack.isEmpty()) {
            return
        }
        val item = currentTab.backStack[currentTab.backStackPosition]
        item.scrollY = webView.scrollY
        model.title?.let {
            item.title.description = it.description
            item.title.thumbUrl = it.thumbUrl
        }
    }

    fun setTab(tab: Tab): Boolean {
        val isDifferent = tab != currentTab
        currentTab = tab
        return isDifferent
    }

    fun goBack(): Boolean {
        if (currentTab.canGoBack()) {
            currentTab.moveBack()
            if (!backStackEmpty()) {
                loadFromBackStack()
                return true
            }
        }
        return false
    }

    fun goForward(): Boolean {
        if (currentTab.canGoForward()) {
            currentTab.moveForward()
            loadFromBackStack()
            return true
        }
        return false
    }

    fun backStackEmpty(): Boolean {
        return currentTab.backStack.isEmpty()
    }

    fun onConfigurationChanged() {
        leadImagesHandler.loadLeadImage()
        bridge.execute(JavaScriptActionHandler.setTopMargin(leadImagesHandler.topMargin))
    }

    private fun commonSectionFetchOnCatch(caught: Throwable) {
        if (!fragment.isAdded) {
            return
        }
        fragment.requireActivity().invalidateOptionsMenu()
        fragment.onPageLoadError(caught)
    }

    private fun pageLoad() {
        pageLoadJob?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
        pageLoadJob = fragment.lifecycleScope.launch(CoroutineExceptionHandler { _, throwable ->
            L.e("Page details network error: ", throwable)
            commonSectionFetchOnCatch(throwable)
        }) {
            if (model.title == null) {
                return@launch
            }
            val title = model.title!!
            model.readingListPage = AppDatabase.instance.readingListPageDao().findPageInAnyList(title)
            model.resetHtmlServedSignal()
            launch(Dispatchers.IO) { revalidateArticleHtml(title) }

            fragment.updateQuickActionsAndMenuOptions()
            fragment.requireActivity().invalidateOptionsMenu()
            fragment.callback()?.onPageUpdateProgressBar(true)
            model.page = null
            val delayLoadHtml = title.prefixedText.contains(":")
            if (!delayLoadHtml) {
                bridge.resetHtml(title)
            }
            if (title.namespace() === Namespace.SPECIAL) {
                // Short-circuit the entire process of fetching the Summary, since Special: pages
                // are not supported in RestBase.
                bridge.resetHtml(title)
                leadImagesHandler.loadLeadImage()
                fragment.requireActivity().invalidateOptionsMenu()
                fragment.onPageMetadataLoaded()
                return@launch
            }

            val pageSummaryRequest = async {
                ServiceFactory.getRest(title.wikiSite).getSummaryResponse(title.prefixedText, cacheControl = model.cacheControl.toString(),
                    saveHeader = if (model.isInReadingList) OfflineCacheInterceptor.SAVE_HEADER_SAVE else null,
                    langHeader = title.wikiSite.languageCode, titleHeader = UriUtil.encodeURL(title.prefixedText))
            }
            val makeWatchRequest = WikipediaApp.instance.isOnline && AccountUtil.isLoggedIn
            val watchedRequest = async {
                try {
                    if (makeWatchRequest) {
                        ServiceFactory.get(title.wikiSite)
                            .getWatchedStatusWithCategories(title.prefixedText)
                    } else if (WikipediaApp.instance.isOnline && !AccountUtil.isLoggedIn) {
                        AnonymousNotificationHelper.maybeGetAnonUserInfo(title.wikiSite)
                    } else {
                        MwQueryResponse()
                    }
                } catch (_: MwNotLoggedInException) {
                    AccountUtil.bailWithLogout()
                    MwQueryResponse()
                } catch (_: IOException) {
                    L.w("Ignoring network error while fetching watched status.")
                    MwQueryResponse()
                }
            }
            val categoriesRequest = async {
                try {
                    if (!makeWatchRequest && WikipediaApp.instance.isOnline) {
                        ServiceFactory.get(title.wikiSite).getCategoriesProps(title.text)
                    } else {
                        MwQueryResponse()
                    }
                } catch (_: IOException) {
                    L.w("Ignoring network error while fetching categories.")
                    MwQueryResponse()
                }
            }
            val pageSummaryResponse = pageSummaryRequest.await()
            val watchedResponse = watchedRequest.await()
            val categoriesResponse = categoriesRequest.await()
            val isWatched = watchedResponse.query?.firstPage()?.watched == true
            val editCount = watchedResponse.query?.userInfo?.editCount ?: 0
            val hasWatchlistExpiry = watchedResponse.query?.firstPage()?.hasWatchlistExpiry() == true
            if (pageSummaryResponse.body() == null) {
                throw RuntimeException("Summary response was invalid.")
            }
            val redirectedFrom = if (pageSummaryResponse.raw().priorResponse?.isRedirect == true) model.title?.displayText else null
            createPageModel(pageSummaryResponse, isWatched, hasWatchlistExpiry)
            if (OfflineCacheInterceptor.SAVE_HEADER_SAVE == pageSummaryResponse.headers()[OfflineCacheInterceptor.SAVE_HEADER]) {
                showPageOfflineMessage(pageSummaryResponse.headers().getInstant("date"))
            }

            val categoryList = (categoriesResponse.query ?: watchedResponse.query)?.firstPage()?.categories?.map { category ->
                Category(title = category.title, lang = title.wikiSite.languageCode)
            }.orEmpty()
            if (categoryList.isNotEmpty()) {
                AppDatabase.instance.categoryDao().upsertAll(categoryList)
            }

            if (delayLoadHtml) {
                bridge.resetHtml(title)
            }
            fragment.onPageMetadataLoaded(redirectedFrom = redirectedFrom, editCount = editCount)

            if (AnonymousNotificationHelper.shouldCheckAnonNotifications(watchedResponse)) {
                checkAnonNotifications(title)
            }
        }
    }

    /**
     * If the article's HTML was served to the WebView from the OkHttp cache (stale-while-revalidate),
     * revalidate it against the network in the background. If it has changed since the cached copy
     * was stored, fetch the fresh HTML into the cache and let the fragment decide how to surface it.
     */
    private suspend fun revalidateArticleHtml(title: PageTitle) {
        val servedFromCache = withTimeoutOrNull(htmlServedTimeoutMs) { model.awaitHtmlServedFromCache() }
        if (servedFromCache != true || !WikipediaApp.instance.isOnline) {
            return
        }
        try {
            val request = Request.Builder()
                    .url(ServiceFactory.getRestBasePath(title.wikiSite) +
                            RestService.PAGE_HTML_ENDPOINT + UriUtil.encodeURL(title.prefixedText))
                    // Match the headers the WebView request sent, so that OkHttp's vary check
                    // passes and we get a cheap conditional revalidation instead of a full fetch.
                    .header("Accept-Language", WikipediaApp.instance.getAcceptLanguage(title.wikiSite))
                    .build()
            OkHttpConnectionFactory.client.newCall(request).execute().use { response ->
                val networkResponse = response.networkResponse
                if (networkResponse != null && networkResponse.code == 200) {
                    // The article changed since the cached copy was stored; drain the body so the
                    // fresh HTML lands in the cache, then let the fragment surface the update.
                    response.body?.bytes()
                    L.d("PageLoadTiming: revalidation found updated article")
                    withContext(Dispatchers.Main) { fragment.onArticleUpdateAvailable() }
                } else {
                    L.d("PageLoadTiming: revalidation found article unchanged (code " + (networkResponse?.code ?: response.code) + ")")
                }
            }
        } catch (t: Throwable) {
            L.e(t)
        }
    }

    private fun checkAnonNotifications(title: PageTitle) {
        fragment.lifecycleScope.launch(CoroutineExceptionHandler { _, throwable ->
            L.e(throwable)
        }) {
            val response = ServiceFactory.get(title.wikiSite)
                .getLastModified(UserTalkAliasData.valueFor(title.wikiSite.languageCode) + ":" + Prefs.lastAnonUserWithMessages)
            if (AnonymousNotificationHelper.anonTalkPageHasRecentMessage(response, title)) {
                fragment.showAnonNotification()
            }
        }
    }

    private fun showPageOfflineMessage(dateHeader: Instant?) {
        if (dateHeader == null) {
            return
        }
        val localDate = LocalDate.ofInstant(dateHeader, ZoneId.systemDefault())
        val dateStr = DateUtil.getShortDateString(localDate)
        Toast.makeText(fragment.requireContext().applicationContext,
            fragment.getString(R.string.page_offline_notice_last_date, dateStr),
            Toast.LENGTH_LONG).show()
    }

    private fun createPageModel(response: Response<PageSummary>,
                                isWatched: Boolean,
                                hasWatchlistExpiry: Boolean) {
        if (response.body() == null) {
            return
        }
        val pageSummary = response.body()
        val page = pageSummary?.toPage(model.title)
        model.page = page
        model.isWatched = isWatched
        model.hasWatchlistExpiry = hasWatchlistExpiry
        model.title = page?.title
        model.title?.let { title ->
            if (!response.raw().request.url.fragment.isNullOrEmpty()) {
                title.fragment = response.raw().request.url.fragment
            }
            if (title.description.isNullOrEmpty()) {
                WikipediaApp.instance.appSessionEvent.noDescription()
            }
            if (!title.isMainPage) {
                title.displayText = page?.summary?.displayTitle.orEmpty()
            }
            title.thumbUrl = pageSummary?.thumbnailUrl
            leadImagesHandler.loadLeadImage()
            fragment.requireActivity().invalidateOptionsMenu()

            // Update our tab list to prevent ZH variants issue.
            WikipediaApp.instance.tabList.getOrNull(WikipediaApp.instance.tabCount - 1)?.setBackStackPositionTitle(title)

            // Update our history entry, in case the Title was changed (i.e. normalized)
            model.curEntry?.let {
                val entry = HistoryEntry(
                    title,
                    it.source,
                    timestamp = it.timestamp
                ).apply {
                    referrer = it.referrer
                    prevId = it.prevId
                }
                model.curEntry = entry

                MainScope().launch {
                    // Insert and/or update this history entry in the DB
                    AppDatabase.instance.historyEntryDao().upsert(entry).run {
                        model.curEntry?.id = this
                    }

                    // Update metadata in the DB
                    AppDatabase.instance.pageImagesDao().upsertForMetadata(entry, title.thumbUrl, title.description, pageSummary?.coordinates?.latitude, pageSummary?.coordinates?.longitude)
                }

                // And finally, count this as a page view.
                WikipediaApp.instance.appSessionEvent.pageViewed(entry)
                ArticleLinkPreviewInteractionEvent(title.wikiSite.dbName(), pageSummary?.pageId ?: 0, entry.source).logNavigate()
            }
        }
    }
}
