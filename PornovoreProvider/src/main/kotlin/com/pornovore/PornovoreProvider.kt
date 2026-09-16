package com.pornovore

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

// ===========================================================================
// Pornovore — films X français (contenu réel, pas de l'animé)
// ===========================================================================
// Site : https://pornovore.fr (tube maison FR, ~1700 films complets 6-45 min).
//   Catégories : /{Cat}/Streaming-…[-page{n}]    (page 1 sans suffixe)
//   Recherche  : /recherche/{q}[-page{n}]
//   Cartes     : <div class="bloc-video"><a href><img alt src>…<p>TITRE</p>
//                + description (pourcentage de likes, durée)
//   Fiche      : JSON-LD VideoObject → embedUrl player.pornovore.fr/…
//   Player     : window.player_args.push({ … "src":[{"…mpd"},{"srcSet":
//                [{src: mp4 720}, {mp4 468}, {mp4 360}] }] }) → MP4 DIRECTS
//                signés (md5 + expiration).
// Les URL des catégories portent le slug complet ("Streaming-porno-francais")
// car la pagination suffixe la même base : …-page2, …-page3…
// ===========================================================================
@CloudstreamPlugin
class PornovorePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(PornovoreProvider())
    }
}

class PornovoreProvider : MainAPI() {
    override var mainUrl = "https://pornovore.fr"
    override var name = "Pornovore"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.NSFW)

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    /** data = "Catégorie|chemin-slug" (le chemin complet de la catégorie). */
    override val mainPage = mainPageOf(
        "Francaise|Streaming-porno-francais" to "🇫🇷 Françaises",
        "Beurette|Streaming-beurette-rebelle" to "🧕 Beurettes",
        "Amateur|Streaming-amateur-x" to "📱 Amateurs",
        "Asiatique|Streaming-porno-asiatique" to "💮 Asiatiques",
        "Black|Streaming-black" to "🖤 Blacks",
        "Latine|Streaming-latina" to "💃 Latines",
        "Lesbienne|Streaming-lesbienne" to "👭 Lesbiennes",
        "Stars-du-x|Streaming-stars-du-porno" to "⭐ Stars du X",
        "Interracial|Streaming-sexe-interracial" to " Interracial",
        "Porno-pour-femme|Streaming-porno-pour-femme" to " Venus (pour elles)",
        "Scene-complete|Streaming-porno-scene-complete" to "🎬 Scènes complètes",
        "Haute-definition|Streaming-porno-HD" to "✨ HD"
    )

    private fun decodeEntities(s: String): String = s
        .replace("&#8217;", "’").replace("&#8216;", "‘")
        .replace("&#8220;", "“").replace("&#8221;", "”")
        .replace("&#8211;", "–").replace("&#8212;", "—")
        .replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /**
     * Cartes : <div class="bloc-video"><a href="…"><img alt="TITRE"
     * src="https://data.pornovore.fr/miniatures/{id}/defaut.jpg">…
     * <p>TITRE</p> … durée <span class="duree">…</span>.
     */
    private fun parseCards(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex("""<div class="bloc-video">\s*<a[^>]+href="([^"]+)"[^>]*>\s*<img[^>]+alt="([^"]*)"[^>]+src="([^"]+)""").findAll(html).forEach { m ->
            val (url, rawTitle, img) = m.destructured
            val title = decodeEntities(rawTitle).trim()
            if (title.isBlank() || !url.contains("Video-")) return@forEach
            out[url] = newMovieSearchResponse(title, url, TvType.NSFW) {
                this.posterUrl = img
            }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (cat, slug) = request.data.split("|")
        val url = if (page <= 1) "$mainUrl/$cat/$slug"
        else "$mainUrl/$cat/$slug-page$page"
        val html = runCatching { app.get(url, headers = baseHeaders).text }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isEmpty()) return emptyList()
        val html = runCatching {
            app.get("$mainUrl/recherche/$q", headers = baseHeaders).text
        }.getOrNull() ?: return emptyList()
        return parseCards(html)
    }

    override suspend fun load(url: String): LoadResponse {
        val html = runCatching { app.get(url, headers = baseHeaders).text }.getOrNull()
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")
        val title = Regex("""<h1[^>]*>([^<]+)</h1>""").find(html)?.groupValues?.get(1)
            ?: Regex("""Video-\d+-([^"']+)""").find(url)?.groupValues?.get(1)?.replace("-", " ")
            ?: url.substringAfterLast('/')
        val poster = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta name="description" content="([^"]+)"""").find(html)?.groupValues?.get(1)?.let { decodeEntities(it) }
        // tags = catégories du film
        val tags = Regex("""href="https://pornovore\.fr/([A-Za-z-]+)/Streaming-[^"]*""""")
            .findAll(html).map { it.groupValues[1].replace("-", " ").lowercase() }.distinct().take(6).toList()
        val duration = Regex(""""duration"\s*:\s*"PT(\d+)M""").find(html)?.groupValues?.get(1)?.toIntOrNull()

        return newMovieLoadResponse(decodeEntities(title), url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.duration = duration?.times(60)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1) page vidéo → embedUrl du player (JSON-LD VideoObject)
        val html = runCatching { app.get(data, headers = baseHeaders).text }.getOrNull() ?: return false
        val embed = Regex(""""embedUrl"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return false
        // 2) page player → window.player_args.push({ … }) → srcSet MP4
        //    (⚠️ le JSON échappe les slashes : https:\/\/… → déséchapper)
        val phtml = runCatching { app.get(embed, headers = baseHeaders).text }.getOrNull() ?: return false
        val argsJson = extractPlayerArgs(phtml) ?: return false
        var found = false
        // src[1].srcSet[] : MP4 signés 720/468/360
        Regex(""""src"\s*:\s*"([^"]*\.mp4[^"]*)"""").findAll(argsJson).forEach { m ->
            var u = m.groupValues[1].replace("\\/", "/")
            if (u.startsWith("//")) u = "https:$u"
            if (!u.startsWith("http")) return@forEach
            found = true
            val q = Regex("""_h264_(\d+)_(\d+)\.mp4""").find(u)
            val quality = q?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val label = if (quality > 0) " · ${quality}p" else ""
            callback(
                ExtractorLink(
                    name, name + label, u, "$mainUrl/",
                    quality = when {
                        quality >= 1080 -> Qualities.P1080.value
                        quality >= 720 -> Qualities.P720.value
                        quality >= 480 -> Qualities.P480.value
                        quality >= 360 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    },
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
        return found
    }

    /** Extrait le JSON du premier window.player_args.push({…}) équilibré. */
    private fun extractPlayerArgs(html: String): String? {
        val marker = "window.player_args.push("
        val start = html.indexOf(marker)
        if (start < 0) return null
        var i = start + marker.length
        var depth = 0
        val out = StringBuilder()
        var inString = false
        var escape = false
        while (i < html.length) {
            val c = html[i]
            out.append(c)
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return out.toString()
                }
            }
            i++
        }
        return null
    }
}
