package com.xvideos

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
// Xvideos (18+) — flux HLS directs
// ===========================================================================
// Site : https://www.xvideos.com (aucune protection sur les pages publiques).
//   · Listes : /new/{n}/ (nouveautés paginées), /best/{yyyy-MM} (classement
//     mensuel — /best/ redirige vers le mois courant).
//   · Recherche : /?k={q}&p={n-1}.
//   · Cartes : <a href="/video.{code}/{slug}"><img data-src="{vignette}"> +
//     titre dans <p class="title"><a href=…>TITRE</a>.
//   · Page vidéo : config html5player avec le manifeste HLS
//     https://hls-cdn77.xvideos-cdn.com/…/hls.m3u8 (480p/720p/1080p).
// ===========================================================================
@CloudstreamPlugin
class XvideosPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(XvideosProvider())
    }
}

class XvideosProvider : MainAPI() {
    override var mainUrl = "https://www.xvideos.com"
    override var name = "Xvideos 18+"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    /** Titre lisible depuis le slug : some_nice_video → Some nice video */
    private fun titleFromSlug(slug: String): String =
        slug.replace('_', ' ').replace("+", " ").trim().replaceFirstChar { it.uppercase() }

    /** Cartes : blocs <div class="thumb-inside"> avec <a href="/video.…"> */
    private fun parseCards(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex(
            """<a href="(/video\.[a-z0-9]+/([^"]+))"[^>]*>\s*<img[^>]+data-src="(https://thumb-cdn77[^"]+)"""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).forEach { m ->
            val href = m.groupValues[1]
            val slug = m.groupValues[2]
            val poster = m.groupValues[3]
            if ("THUMBNUM" in href || "THUMBNUM" in slug) return@forEach
            val url = "$mainUrl/$href".substringBefore("?")
            if (out.containsKey(url)) return@forEach
            // titre exact si présent dans <p class="title"> pour ce lien
            val exact = Regex(
                """<p class="title">\s*<a href="${Regex.escape(href)}"[^>]*>([^<]+)</a>"""
            ).find(html)?.groupValues?.get(1)?.trim()
            out[url] = newMovieSearchResponse(
                exact?.takeIf { it.isNotBlank() } ?: titleFromSlug(slug),
                url, TvType.NSFW
            ) {
                this.posterUrl = poster
            }
        }
        return out.values.toList()
    }

    override val mainPage = mainPageOf(
        "new" to "🆕 Nouveautés",
        "best" to "🏆 Meilleurs du mois"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "best" -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                val ym = java.time.LocalDate.now().let { "%04d-%02d".format(it.year, it.monthValue) }
                "$mainUrl/best/$ym/"
            }
            else -> "$mainUrl/new/$page/"
        }
        val html = runCatching { app.get(url, headers = baseHeaders).text }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        return newHomePageResponse(request, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val html = runCatching {
            app.get("$mainUrl/?k=$q", headers = baseHeaders).text
        }.getOrNull() ?: return emptyList()
        return parseCards(html)
    }

    override suspend fun load(url: String): LoadResponse {
        val html = runCatching { app.get(url, headers = baseHeaders).text }.getOrNull()
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Vidéo introuvable")
        val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1)?.substringBefore(" - XVIDEOS")
            ?: "Vidéo"
        val poster = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
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
        // manifeste HLS (multi-qualités)
        Regex("""https://hls-cdn77\.xvideos-cdn\.com/[^"'\s\\]+\.m3u8""").findAll(html)
            .map { it.value }
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
        // fichiers MP4 progressifs (présents selon les vidéos)
        Regex("""https://cdn[0-9a-z-]+\.xvideos-cdn\.com/[^"'\s\\]+\.mp4[^"'\s\\]*""").findAll(html)
            .map { it.value }
            .distinct()
            .forEach { u ->
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
