package com.hentaistream

import android.content.Context
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// ===========================================================================
// HentaiStream — hentai EN sous-titré (WordPress + player-logic « zarat »)
// ===========================================================================
// Site : https://hentaistream.io
//   Listes    : / (40 cartes) paginées /page/N/ · genres /genre/{slug}/page/N/
//               cartes = <a href=/watch/{slug}/> + <img data-src> (lazy)
//   Série     : /watch/{slug}/ → liens /watch/{slug}/episode-{n}
//   Épisode   : /watch/{slug}/episode-{n} → iframe
//               //hentaistream.io/wp-content/plugins/player-logic/player.php?data={b64}
//               → meta x-secure-token="sha512-{b64}"
//   Décodeur  : strip « sha512- » puis 3 × { ROT13 → base64-décoder } → JSON
//               { en, iv, uri } (uri = …/player-logic/)
//   Playlist  : POST {uri}api.php  (form : action=zarat_get_data_player_ajax,
//               a=en, b=iv — ⚠ url-encoder les « + » du base64)
//               → data.sources[].src = https://octopusmanifest.org/{uuid}/playlist.m3u8
// ===========================================================================
@CloudstreamPlugin
class HentaiStreamPlugin : Plugin() {
    override fun load(context: Context) {
        HentaiStreamProvider.appContext = context.applicationContext
        registerMainAPI(HentaiStreamProvider())
        openSettings = { ctx -> HentaiStreamProvider.showSettings(ctx) }
    }
}

class HentaiStreamProvider : MainAPI() {
    companion object {
        const val DEFAULT_URL = "https://hentaistream.io"

        @Volatile
        var appContext: Context? = null

        private const val PREFS_NAME = "hentaistream_settings"
        private const val PREF_URL = "site_url"

        private val cfKiller by lazy { CloudflareKiller() }

        fun currentUrl(): String = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(PREF_URL, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull() ?: DEFAULT_URL

        fun setSiteUrl(context: Context, url: String?) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .apply()
        }

        fun showSettings(context: Context) {
            val input = android.widget.EditText(context).apply {
                setText(currentUrl()); hint = "https://hentaistream.io"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de HentaiStream")
                .setMessage("Si le site change de domaine, indiquez l'adresse actuelle.")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setSiteUrl(context, DEFAULT_URL) }
                .show()
        }

        /** ROT13 sur les lettres (a-z, A-Z). */
        private fun String.rot13(): String = buildString {
            for (c in this@rot13) {
                append(
                    when {
                        c in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                        c in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                        else -> c
                    }
                )
            }
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "HentaiStream"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    private fun headers(referer: String? = null) = buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept-Language", "en-US,en;q=0.9,fr;q=0.8")
        if (referer != null) put("Referer", referer)
    }

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    private suspend fun httpGet(url: String, referer: String? = null): String? =
        runCatching { app.get(url, headers = headers(referer), interceptor = cfKiller).text }.getOrNull()

    override val mainPage = mainPageOf(
        "" to "🆕 Derniers",
        "harem" to "🎀 Harem",
        "bdsm" to "⛓️ BDSM",
        "yuri" to "百合 Yuri",
        "yaoi" to "💝 Yaoi",
        "ahegao" to "😵 Ahegao",
        "big-breasts" to "👙 Big Breasts",
        "monster" to "👹 Monster",
        "young-hentai" to "🔞 Young"
    )

    // -------------------------------------------------------------------------
    // Cartes : <a href=/watch/{slug}/ …><img data-src=…> + titre (title= ou <h3>)
    // -------------------------------------------------------------------------
    private fun parseCards(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        // home/genres : <img data-src=…> (lazy) ; recherche : <img src=…>
        Regex("""<a href="(?:https?://[^"]*?/watch/([a-z0-9-]+)/?)"[^>]*title="([^"]*)"[^>]*>[\s\S]{0,900}?<img[^>]*>""")
            .findAll(html).forEach { m ->
                val slug = m.groupValues[1]
                val title = m.groupValues[2].trim().takeIf { it.isNotBlank() } ?: return@forEach
                val imgTag = Regex("""<img[^>]*>""").find(m.groupValues[0])?.groupValues?.get(0).orEmpty()
                val img = Regex("""data-src="([^"]+)"""").find(imgTag)?.groupValues?.get(1)
                    ?: Regex("""\ssrc="([^"]+)"""").find(imgTag)?.groupValues?.get(1)
                out[slug] = newMovieSearchResponse(title, "$mainUrl/watch/$slug/", TvType.NSFW) {
                    this.posterUrl = img
                }
            }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val url = if (request.data.isBlank()) {
            if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            "$mainUrl/genre/${request.data}/page/$page/"
        }
        val html = httpGet(url) ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        return newHomePageResponse(request, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val html = httpGet("$mainUrl/?s=$q") ?: return emptyList()
        return parseCards(html)
    }

    // -------------------------------------------------------------------------
    // Fiche série + épisodes /watch/{slug}/episode-{n}
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val html = httpGet(url) ?: throw ErrorLoadingException()
        val slug = url.trimEnd('/').substringAfterLast('/')
        val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.trim()
            ?: Regex("""<h1[^>]*>([^<]+)</h1>""").find(html)?.groupValues?.get(1)?.trim()
            ?: Regex("""<title>([^<|–-]+)""").find(html)?.groupValues?.get(1)?.trim()
            ?: slug.replace('-', ' ')
        val poster = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta property="og:description" content="([^"]*)"""").find(html)?.groupValues?.get(1)

        // épisodes : <ul class="main version-chap"> → /watch/{slug}/episode-{n}
        // (+ vignette de l'épisode dans .chapter-thumbnail si présente)
        val epMap = sortedMapOf<Int, String?>()
        Regex("""href="[^"]*?/watch/$slug/episode-(\d+)/?"[\s\S]{0,400}?<img[^>]*>""").findAll(html).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@forEach
            val imgTag = Regex("""<img[^>]*>""").find(m.groupValues[0])?.groupValues?.get(0).orEmpty()
            val thumb = Regex("""\ssrc="([^"]+)"""").find(imgTag)?.groupValues?.get(1)
                ?: Regex("""data-src="([^"]+)"""").find(imgTag)?.groupValues?.get(1)
            epMap[n] = thumb ?: poster
        }
        if (epMap.isEmpty()) {
            Regex("""href="[^"]*?/watch/$slug/episode-(\d+)/?"""").findAll(html).forEach {
                it.groupValues[1].toIntOrNull()?.let { n -> epMap[n] = poster }
            }
        }
        if (epMap.isEmpty()) {
            // pas d'épisodes listés → fiche mono-épisode : le player est sur la fiche
            return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        val episodes = epMap.map { (n, thumb) ->
            newEpisode("$mainUrl/watch/$slug/episode-$n/") {
                this.name = "Épisode $n"
                this.episode = n
                this.posterUrl = thumb ?: poster
            } as Episode
        }
        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // -------------------------------------------------------------------------
    // Décodeur du token : « sha512- » + 3 × {ROT13 → base64}
    // -------------------------------------------------------------------------
    private fun decodeSecureToken(token: String): String? = runCatching {
        var s = token.trim().removePrefix("sha512-")
        repeat(3) {
            s = String(Base64.decode(s.rot13(), Base64.DEFAULT), Charsets.UTF_8)
        }
        s
    }.getOrNull()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayerConfig(
        val en: String? = null,
        val iv: String? = null,
        val uri: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ZaratSource(val src: String? = null, val label: String? = null, val type: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ZaratData(val sources: List<ZaratSource> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ZaratResponse(val status: Boolean? = false, val data: ZaratData? = null)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val html = httpGet(data) ?: return false

        // 1) iframe player-logic : player.php?data={b64}
        val b64 = Regex("""player\.php\?data=([A-Za-z0-9+/=_-]+)""").find(html)?.groupValues?.get(1)
        if (b64 != null) {
            val playerUrl = "$mainUrl/wp-content/plugins/player-logic/player.php?data=$b64"
            val playerHtml = httpGet(playerUrl, referer = data) ?: return false
            // 2) meta x-secure-token="sha512-{…}"
            val token = Regex("""x-secure-token=["']sha512-([^"']+)["']""").find(playerHtml)?.groupValues?.get(1)
                ?: Regex("""sha512-([A-Za-z0-9+/=]+)""").find(playerHtml)?.groupValues?.get(1)
            if (token != null) {
                val cfg = decodeSecureToken(token)?.let { runCatching { AppUtils.parseJson<PlayerConfig>(it) }.getOrNull() }
                if (cfg?.en != null && cfg.iv != null) {
                    val uri = (cfg.uri ?: "$mainUrl/wp-content/plugins/player-logic/")
                        .let { if (it.startsWith("//")) "https:$it" else it }
                        .trimEnd('/') + "/"
                    // 3) POST api.php — ⚠ form-urlencoded STRICT (les « + » du
                    //    base64 doivent être encodés %2B, sinon HTTP 500)
                    val resp = runCatching {
                        app.post(
                            "${uri}api.php",
                            headers = headers(data) + mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to mainUrl
                            ),
                            data = mapOf(
                                "action" to "zarat_get_data_player_ajax",
                                "a" to cfg.en,
                                "b" to cfg.iv
                            ),
                            interceptor = cfKiller
                        ).text
                    }.getOrNull()
                    val sources = resp?.let { runCatching { AppUtils.parseJson<ZaratResponse>(it) }.getOrNull() }?.data?.sources
                    var found = false
                    sources?.forEach { s ->
                        val src = s.src?.takeIf { it.startsWith("http") } ?: return@forEach
                        if (src.contains(".m3u8")) {
                            // résolution des qualités HLS (octopus = multi-qualités)
                            runCatching {
                                M3u8Helper.generateM3u8(
                                    s.label ?: "HentaiStream",
                                    src,
                                    "$mainUrl/",
                                    headers = mapOf("User-Agent" to USER_AGENT)
                                ).forEach { q ->
                                    found = true
                                    callback(q)
                                }
                            }.onFailure {
                                found = true
                                callback(
                                    newExtractorLink(s.label ?: "HentaiStream", s.label ?: "HentaiStream", src) {
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        } else {
                            found = true
                            callback(
                                newExtractorLink(s.label ?: "HentaiStream", s.label ?: "HentaiStream", src) {
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                    if (found) return true
                }
            }
        }

        // 4) fallback : iframes génériques de la page (autres lecteurs WP)
        var found = false
        Regex("""<iframe[^>]*\ssrc="(https?://[^"]+)"""").findAll(html).forEach { m ->
            val u = m.groupValues[1]
            if ("player.php" in u || "player-logic" in u) return@forEach
            runCatching {
                loadExtractor(u, data, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            }
        }
        return found
    }
}
