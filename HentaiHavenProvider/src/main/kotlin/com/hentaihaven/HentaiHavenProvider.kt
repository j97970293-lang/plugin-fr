package com.hentaihaven

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
// HentaiHaven Rule34 (18+) — vidéos Rule34 animées
// ===========================================================================
// Site : https://hentaihaven.xxx (Next.js). La section « hentai » /watch/ charge
// son flux côté client (non extractible simplement) ; la section Rule34, elle,
// expose tout :
//   · Listing & recherche : API WordPress headless publique
//     https://cms.hentaihaven.xxx/wp-json/wp/v2/rule34-video
//       ?per_page=24&page=N&_embed=wp:featured_media[&search=q]
//     → posts [{slug, title:{rendered}}] + miniature dans _embedded
//       (https://cdnr-octopus.hhpanel.org/…/thumb_001.webp).
//   · Page vidéo : https://hentaihaven.xxx/rule34/video/{slug}/ contient le
//     manifeste HLS en clair : https://octopusmanifest.org/{uuid}/playlist.m3u8
//     (1080p/720p/480p + piste audio) — lien direct.
// ===========================================================================
@CloudstreamPlugin
class HentaiHavenPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(HentaiHavenProvider())
    }
}

class HentaiHavenProvider : MainAPI() {
    override var mainUrl = "https://hentaihaven.xxx"
    override var name = "HentaiHaven Rule34 18+"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    private val cmsUrl = "https://cms.hentaihaven.xxx/wp-json/wp/v2/rule34-video"

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json"
    )

    /** Décode les entités HTML courantes des titres WordPress. */
    private fun decodeEntities(s: String): String = s
        .replace("&#8217;", "’").replace("&#8220;", "“").replace("&#8221;", "”")
        .replace("&#8211;", "–").replace("&#8212;", "—").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** Parse les posts WP du JSON (slug + titre + miniature _embedded). */
    private fun parsePosts(jsonRaw: String): List<SearchResponse> {
        val json = jsonRaw.replace("\\/", "/")
        val out = LinkedHashMap<String, SearchResponse>()
        // un bloc par post : on découpe sur {"id": pour éviter les collages
        Regex("""\{"id":\d+,"date"""").split(json).drop(1).forEach { block ->
            val slug = Regex(""""slug"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: return@forEach
            val title = Regex(""""title"\s*:\s*\{[^}]*"rendered"\s*:\s*"([^"]*)"""")
                .find(block)?.groupValues?.get(1)?.let { decodeEntities(it) }?.trim()
                ?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
            val thumb = Regex("""https://cdnr-octopus\.hhpanel\.org/[^"\\]+\.(?:webp|jpe?g|png)""")
                .find(block)?.value
            val url = "$mainUrl/rule34/video/$slug/"
            out[url] = newMovieSearchResponse(title, url, TvType.NSFW) {
                this.posterUrl = thumb
            }
        }
        return out.values.toList()
    }

    override val mainPage = mainPageOf(
        "recent" to "🆕 Dernières vidéos",
        "updated" to "🔄 Mises à jour"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val order = if (request.data == "updated") "modified" else "date"
        val json = runCatching {
            app.get("$cmsUrl?per_page=24&page=$page&orderby=$order&_embed=wp:featured_media", headers = baseHeaders).text
        }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
        val items = parsePosts(json)
        return newHomePageResponse(request, items, hasNext = items.size >= 24)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val json = runCatching {
            app.get("$cmsUrl?per_page=30&page=1&search=$q&_embed=wp:featured_media", headers = baseHeaders).text
        }.getOrNull() ?: return emptyList()
        return parsePosts(json)
    }

    override suspend fun load(url: String): LoadResponse {
        val html = runCatching { app.get(url, headers = baseHeaders).text }.getOrNull()
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Vidéo introuvable")
        val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1)?.substringBefore(" - ")
            ?: "Vidéo"
        val poster = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta (?:property="og:description"|name="description") content="([^"]*)"""")
            .find(html)?.groupValues?.get(1)
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
        Regex("""https://octopusmanifest\.org/[a-zA-Z0-9-]+/playlist\.m3u8""").findAll(html).map { it.value }.distinct().forEach { u ->
                found = true
                callback(
                    newExtractorLink(name, "$name · HLS", u) {
                    this.referer = "$mainUrl/"
                    this.type = ExtractorLinkType.M3U8
                }
                )
            }
        return found
    }
}
