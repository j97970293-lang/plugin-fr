package com.hentaivost

import android.content.Context
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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

// ===========================================================================
// Hentai-VOSTFR — hentai VOSTFR (WordPress Cactus, protégé Cloudflare)
// ===========================================================================
// Site : https://hentaivost.fr (réseau Hentai Paradise ; même catalogue que
// hentai-fap.fr — les slugs vidéos sont identiques).
//   ⚠ Cloudflare « Just a moment » sur IP datacenter : structure vérifiée
//   via les instantanés Wayback ; sur l'appareil, CloudflareKiller règle
//   le défi dans la WebView.
//   Listes    : /hp/hentai-video/ (+ /news/, /reupload/, ?genre={tag})
//               pagination /page/N/ — cartes : <div class="picture-content">
//               → <a href=/{slug}/ title=TITRE> + <img src=…>
//   Recherche : /?s={q} (WordPress standard, mêmes cartes)
//   Fiche     : /{slug}/ → og:title / og:image / og:description
//   Lecture   : la page vidéo porte le lecteur du réseau ; si celui-ci
//   n'est pas lisible, on emprunte le chemin VALIDÉ du même catalogue sur
//   hentai-fap.fr : /hentai-video/{slug} → div#stream_ep (data-v, data-f)
//   → /ajax/csrfToken.php → /ajax/getlink.php → MP4 signé.
// ===========================================================================
@CloudstreamPlugin
class HentaiVostPlugin : Plugin() {
    override fun load(context: Context) {
        HentaiVostProvider.appContext = context.applicationContext
        registerMainAPI(HentaiVostProvider())
        openSettings = { ctx -> HentaiVostProvider.showSettings(ctx) }
    }
}

class HentaiVostProvider : MainAPI() {
    companion object {
        const val DEFAULT_URL = "https://hentaivost.fr"
        const val DEFAULT_BRIDGE = "https://hentai-fap.fr"

        @Volatile
        var appContext: Context? = null

        private const val PREFS_NAME = "hentaivost_settings"
        private const val PREF_URL = "site_url"
        private const val PREF_BRIDGE = "bridge_url"

        fun currentUrl(): String = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(PREF_URL, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull() ?: DEFAULT_URL

        fun currentBridge(): String = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(PREF_BRIDGE, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull() ?: DEFAULT_BRIDGE

        fun setUrls(context: Context, url: String?, bridge: String?) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .putString(PREF_BRIDGE, bridge?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .apply()
        }

        fun showSettings(context: Context) {
            val inputSite = android.widget.EditText(context).apply {
                setText(currentUrl()); hint = "https://hentaivost.fr"
            }
            val inputBridge = android.widget.EditText(context).apply {
                setText(currentBridge()); hint = "https://hentai-fap.fr (lecteurs)"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(inputSite)
                addView(inputBridge)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de Hentai-VOSTFR")
                .setMessage("Si le site change de domaine, indiquez l'adresse actuelle. Le second champ est le site qui sert les lecteurs (réseau Hentai Paradise).")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setUrls(context, inputSite.text.toString(), inputBridge.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setUrls(context, DEFAULT_URL, DEFAULT_BRIDGE) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "Hentai-VOSTFR"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.NSFW)

    private val cfKiller by lazy { CloudflareKiller() }

    private fun headers(referer: String? = null) = buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept-Language", "fr-FR,fr;q=0.9")
        if (referer != null) put("Referer", referer)
    }

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    private suspend fun httpGet(url: String, referer: String? = null): String? = runCatching {
        app.get(url, headers = headers(referer), interceptor = cfKiller).text
    }.getOrNull()

    override val mainPage = mainPageOf(
        "news" to "🆕 Nouveautés",
        "" to "🔥 Tous les hentai",
        "reupload" to "🔁 Reupload",
        "harem" to "🎀 Harem",
        "incest" to "👪 Famille",
        "vanilla" to "💌 Vanilla",
        "ahegao" to "😵 Ahegao",
        "futanari" to "🍑 Futanari"
    )

    // -------------------------------------------------------------------------
    // Cartes Cactus : <div class="picture-content"> → <a href=/{slug}/
    // title=TITRE> … <img src=…> (les articles de blog n'ont pas ce bloc)
    // -------------------------------------------------------------------------
    private fun parseCards(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex(
            """<div class="picture-content">\s*<a href="(?:https?://[^"]*?)/([a-z0-9%.-]{6,})/"[^>]*title="([^"]+)""" +
                """"[\s\S]{0,500}?<img[^>]+src="([^"]+)""""
        ).findAll(html).forEach { m ->
            val (slug, title, img) = m.destructured
            if (title.isBlank()) return@forEach
            out[slug] = newMovieSearchResponse(title, "$mainUrl/$slug/", TvType.NSFW) {
                this.posterUrl = img
            }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val url = when {
            request.data.isBlank() && page <= 1 -> "$mainUrl/hp/hentai-video/"
            request.data.isBlank() -> "$mainUrl/hp/hentai-video/page/$page/"
            request.data in setOf("news", "reupload") && page <= 1 -> "$mainUrl/hp/hentai-video/${request.data}/"
            request.data in setOf("news", "reupload") -> "$mainUrl/hp/hentai-video/${request.data}/page/$page/"
            page <= 1 -> "$mainUrl/hp/hentai-video/?genre=${request.data}"
            else -> "$mainUrl/hp/hentai-video/page/$page/?genre=${request.data}"
        }
        val html = httpGet(url) ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        return newHomePageResponse(request, items, hasNext = items.size >= 8)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isEmpty()) return emptyList()
        val html = httpGet("$mainUrl/?s=$q") ?: return emptyList()
        return parseCards(html)
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val html = httpGet(url) ?: throw ErrorLoadingException()
        val slug = url.trimEnd('/').substringAfterLast('/')
        val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.trim()
            ?: Regex("""<title>([^<|–-]+)""").find(html)?.groupValues?.get(1)?.trim()
            ?: slug.replace('-', ' ')
        val poster = Regex("""<meta property="og:image" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta property="og:description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta name="description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // -------------------------------------------------------------------------
    // Lecture : page hentaivost (lecteur du réseau) sinon pont hentai-fap
    // (même slug) avec la chaîne validée csrfToken → getlink → MP4 signé
    // -------------------------------------------------------------------------
    private data class StreamEp(val v: String, val f: String, val type: String, val server: String)

    private fun findStreamEp(html: String): StreamEp? {
        val ep = Regex("""<div id="stream_ep"[^>]*>""").find(html)?.groupValues?.get(0) ?: return null
        val v = Regex("""data-v="([^"]+)"""").find(ep)?.groupValues?.get(1) ?: return null
        val f = Regex("""data-f="([^"]+)"""").find(ep)?.groupValues?.get(1) ?: return null
        val type = Regex("""data-type="([^"]*)"""").find(ep)?.groupValues?.get(1) ?: "video"
        val server = Regex("""data-server="([^"]+)"""").find(ep)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
            ?: "https://video.hentai-manga.io"
        return StreamEp(v, f, type, server)
    }

    /** Chaîne validée sur hentai-fap.fr : csrfToken → getlink → URL signée. */
    private suspend fun fapSignedLink(base: String, ep: StreamEp, referer: String): String? {
        val path = if (ep.type == "clip") "/${ep.f}/${ep.v}" else "/movies/${ep.f}/${ep.v}"
        val token = runCatching {
            app.get(
                "$base/ajax/csrfToken.php",
                headers = headers(referer) + mapOf("X-Requested-With" to "XMLHttpRequest"),
                interceptor = cfKiller
            ).text
        }.getOrNull()?.let { Regex(""""token"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        val postHeaders = buildMap {
            putAll(headers(referer))
            put("X-Requested-With", "XMLHttpRequest")
            put("Content-Type", "application/x-www-form-urlencoded")
            if (token != null) put("X-CSRF-Token", token)
        }
        val signed = runCatching {
            app.post(
                "$base/ajax/getlink.php",
                headers = postHeaders,
                data = mapOf("file" to path),
                interceptor = cfKiller
            ).text.trim()
        }.getOrNull() ?: return null
        if (signed.startsWith("{") || !signed.startsWith("/")) return null
        return "${ep.server}$signed"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val slug = data.trimEnd('/').substringAfterLast('/')
        val html = httpGet(data)

        // 1) lecteur directement dans la page hentaivost
        if (html != null) {
            findStreamEp(html)?.let { ep ->
                val link = fapSignedLink(currentBridge(), ep, data) ?: return@let
                callback(
                    ExtractorLink(
                        name, name, link, "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
            // iframes du lecteur réseau (player?v=…)
            Regex("""<iframe[^>]*\ssrc="(https?://[^"]+)"""").findAll(html).forEach { m ->
                val u = m.groupValues[1]
                if ("player?v=" in u) {
                    runCatching {
                        loadExtractor(u, data, subtitleCallback) { l -> callback(l) }
                    }
                    return true
                }
            }
        }

        // 2) pont : même slug sur hentai-fap.fr (chaîne validée)
        val bridge = currentBridge()
        val fapHtml = httpGet("$bridge/hentai-video/$slug", referer = "$bridge/")
        val ep = fapHtml?.let { findStreamEp(it) } ?: return false
        val link = fapSignedLink(bridge, ep, "$bridge/hentai-video/$slug") ?: return false
        callback(
            ExtractorLink(
                name, name, link, "$bridge/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
