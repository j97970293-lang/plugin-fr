package com.hentaicity

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

// ===========================================================================
// HentaiCity (18+) — vidéos hentai / 3D animées, HLS directs
// ===========================================================================
// Site : https://www.hentaicity.com (PHP classique, aucune protection).
//   · Listes : /videos/all-recent-{n}.html, all-view-, all-rate- ;
//     catégories : /videos/straight/{cat}-popular[-{n}].html (3d, anal…).
//   · Cartes : <a href="{url}.html"><img src="{cdn}" alt="{titre}">
//     (les href passent par /click/1-1/video/… → 302 vers /video/{slug}.html).
//   · Recherche : /customsearch.php?view=search&search_type=video&search={q}
//     &main_cat=0 → 302 /search/video/{q}.
//   · Page vidéo : <video> + flux HLS signé hls.hentaicity.com/…master.m3u8
//     (238p/360p/396p/…) + MP4 de secours /flv/{id}/mobile.mp4.
// ===========================================================================
@CloudstreamPlugin
class HentaiCityPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(HentaiCityProvider())
    }
}

class HentaiCityProvider : MainAPI() {
    override var mainUrl = "https://www.hentaicity.com"
    override var name = "HentaiCity 18+"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    /** Cartes d'une page de listing : <a href="…html"><img src alt="titre"> */
    private fun parseCards(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex(
            """<a[^>]+href="([^"]+\.html)"[^>]*>\s*<img[^>]+src="([^"]+)"[^>]+alt="([^"]*)"""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).forEach { m ->
            val href = m.groupValues[1]
            val poster = m.groupValues[2]
            val title = m.groupValues[3].trim()
            if (title.isBlank() || !href.contains("/video/", true) && !href.contains("/click/")) return@forEach
            val url = if (href.startsWith("http")) href else "$mainUrl$href"
            if (out.containsKey(url)) return@forEach
            out[url] = newMovieSearchResponse(title, url, TvType.NSFW) {
                this.posterUrl = poster
                this.posterHeaders = baseHeaders
            }
        }
        return out.values.toList()
    }

    override val mainPage = mainPageOf(
        "recent" to "🆕 Récents",
        "view" to "🔥 Populaires",
        "rate" to "⭐ Top notés",
        "3d" to "🎮 3D",
        "anal" to "🍑 Anal"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "view" -> "$mainUrl/videos/all-view-${page}.html"
            "rate" -> "$mainUrl/videos/all-rate-${page}.html"
            "3d" -> "$mainUrl/videos/straight/3d-popular${if (page > 1) "-$page" else ""}.html"
            "anal" -> "$mainUrl/videos/straight/anal-popular${if (page > 1) "-$page" else ""}.html"
            else -> "$mainUrl/videos/all-recent-$page.html"
        }
        val html = runCatching { app.get(url, headers = baseHeaders).text }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        return newHomePageResponse(request, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val html = runCatching {
            app.get(
                "$mainUrl/customsearch.php?view=search&search_type=video&search=$q&main_cat=0",
                headers = baseHeaders
            ).text
        }.getOrNull() ?: return emptyList()
        return parseCards(html)
    }

    override suspend fun load(url: String): LoadResponse {
        val html = runCatching { app.get(url, headers = baseHeaders).text }.getOrNull()
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Vidéo introuvable")
        val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1)?.substringBefore(" - ")
            ?: "Vidéo"
        val poster = Regex("""<video[^>]*poster="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta name="description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        return newMovieLoadResponse(title.trim(), url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching { app.get(data, headers = baseHeaders).text }.getOrNull() ?: return false
        var found = false
        // HLS signé multi-qualités
        Regex("""https://hls\.hentaicity\.com/[^"'\s<>]+master\.m3u8[^"'\s<>]*""").findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .distinct()
            .forEach { u ->
                found = true
                callback(
                    newExtractorLink(name, "$name · HLS", u) {
                    this.referer = "$mainUrl/"
                    this.type = ExtractorLinkType.M3U8
                }
                )
            }
        // MP4 de secours
        Regex("""https://www\.hentaicity\.com/flv/[a-z0-9/]+\.mp4""").findAll(html).map { it.value }.distinct().forEach { u ->
                found = true
                callback(
                    newExtractorLink(name, "$name · MP4", u) {
                    this.referer = "$mainUrl/"
                    this.type = ExtractorLinkType.VIDEO
                }
                )
            }
        return found
    }
}
