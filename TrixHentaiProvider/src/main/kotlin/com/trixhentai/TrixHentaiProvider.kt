package com.trixhentai

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
// TrixHentai — hentai & animes pour adultes VOSTFR (français)
// ===========================================================================
// Site : https://www.trixhentai.com (WordPress, thème VideoTube).
//   Catalogue : /categories/{slug}/page/{n}/ (page 1 sans suffixe)
//   Recherche : /?s={q} (mêmes cartes)
//   Cartes    : <div class="item…"><a href="/video/{slug}/"> + <picture>
//               (data-srcset) + <h3><a>TITRE</a></h3>
//   Fiche     : og:title / og:image / og:description + jwplayer avec
//               file: 'https://…mp4' → MP4 DIRECT.
// Le player jwplayer porte aussi une pub VAST (pré-roll) : on prend
// directement l'URL du fichier, pas le player.
// ===========================================================================
@CloudstreamPlugin
class TrixHentaiPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        TrixHentaiProvider.appContext = context.applicationContext
        registerMainAPI(TrixHentaiProvider())
        openSettings = { ctx -> TrixHentaiProvider.showSettings(ctx) }
    }
}

class TrixHentaiProvider : MainAPI() {
    companion object {
        const val DEFAULT_URL = "https://www.trixhentai.com"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "trixhentai_settings"
        private const val PREF_URL = "site_url"

        fun currentUrl(): String = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                ?.getString(PREF_URL, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull() ?: DEFAULT_URL

        fun setSiteUrl(context: android.content.Context, url: String?) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .apply()
        }

        fun showSettings(context: android.content.Context) {
            val input = android.widget.EditText(context).apply {
                setText(currentUrl()); hint = "https://www.trixhentai.com"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de TrixHentai")
                .setMessage("Si le site change de domaine, indiquez l'adresse actuelle.")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setSiteUrl(context, DEFAULT_URL) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "TrixHentai"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.NSFW)

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    /** Slug des catégories → titre de section (une carte par vidéo). */
    override val mainPage = mainPageOf(
        "vostfr" to "🇫🇷 VOSTFR",
        "non-censure" to "🔓 Non censuré",
        "futanari" to "🍑 Futanari",
        "hentai-yuri" to "百合 Yuri",
        "naruto" to "🥷 Naruto",
        "one-piece" to "🏴‍☠️ One Piece",
        "dragon-ball" to "🐉 Dragon Ball",
        "mha" to "💥 MHA",
        "pokemon" to "⚡ Pokémon",
        "cosplayeuses" to "📸 Cosplay +18",
        "intelligence-artificielle" to "🤖 Intelligence artificielle",
        "indie" to "🎬 Indie"
    )

    private fun decodeEntities(s: String): String = s
        .replace("&#8217;", "’").replace("&#8216;", "‘")
        .replace("&#8220;", "“").replace("&#8221;", "”")
        .replace("&#8211;", "–").replace("&#8212;", "—")
        .replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /**
     * Cartes du thème VideoTube : bloc <div class="item…"> contenant
     * <h3><a href="…/video/{slug}/">TITRE</a></h3> ; l'affiche est le
     * data-srcset/src <img> du même bloc (on prend la plus grande).
     */
    private fun parseCards(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex("""<div class="item responsive-height[\s\S]*?(?=<div class="item |$)""").findAll(html).forEach { b ->
            val block = b.value
            val m = Regex("""<h3><a href="($mainUrl/video/[^"]+)">([^<]+)</a></h3>""").find(block) ?: return@forEach
            val (url, rawTitle) = m.destructured
            val title = decodeEntities(rawTitle).trim()
            if (title.isBlank()) return@forEach
            val img = Regex("""(?:data-srcset|srcset)="([^"]+)""").findAll(block)
                .flatMap { it.groupValues[1].split(",") }
                .map { it.trim().substringBefore(" ") }
                .lastOrNull { it.startsWith("http") }
                ?: Regex("""src="([^"]+)""").findAll(block)
                    .map { it.groupValues[1] }.lastOrNull { it.startsWith("http") && !it.contains("data:image") }
            out[url] = newMovieSearchResponse(title, url, TvType.NSFW) {
                this.posterUrl = img
            }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val url = if (page <= 1) "$mainUrl/categories/${request.data}/"
        else "$mainUrl/categories/${request.data}/page/$page/"
        val html = runCatching { app.get(url, headers = baseHeaders).text }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isEmpty()) return emptyList()
        val html = runCatching {
            app.get("$mainUrl/?s=$q", headers = baseHeaders).text
        }.getOrNull() ?: return emptyList()
        return parseCards(html)
    }

    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val html = runCatching { app.get(url, headers = baseHeaders).text }.getOrNull()
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")
        fun og(prop: String): String? =
            Regex("""<meta property="og:$prop" content="([^"]*)"""").find(html)?.groupValues?.get(1)

        val title = (og("title")?.substringBefore(" - Trixhentai") ?: url.trimEnd('/').substringAfterLast('/'))
            .let { decodeEntities(it) }
        val poster = og("image")
        val plot = og("description")?.let { decodeEntities(it) }
        val tags = Regex("""<a href="https://www\.trixhentai\.com/categories/([^"]+)/"""")
            .findAll(html).map { it.groupValues[1].replace("-", " ") }.distinct().take(6).toList()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val html = runCatching { app.get(data, headers = baseHeaders).text }.getOrNull() ?: return false
        var found = false
        // jwplayer setup : file: 'https://….mp4' (MP4 direct servi par le site)
        Regex("""file\s*:\s*['"](https?://[^'"]+\.(?:mp4|m3u8))['"]""").findAll(html).forEach { m ->
            val u = m.groupValues[1]
            found = true
            callback(
                ExtractorLink(
                    name, name, u, "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    type = if (".m3u8" in u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        return found
    }
}
