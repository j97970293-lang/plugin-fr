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
// Hentai-VOSTFR — hentai VOSTFR (réseau Hentai Paradise)
// ===========================================================================
// Site : https://hentaivost.fr (réseau Hentai Paradise ; même catalogue que
// hentai-fap.fr — les slugs vidéos sont identiques).
//   ⚠ v2 : hentaivost.fr reste derrière un défi Cloudflare que même
//   CloudflareKiller ne résout pas sur certains appareils → l'extension
//   s'appuie désormais directement sur le PONT hentai-fap.fr (réseau
//   identique, slugs identiques, lecteurs identiques) pour le catalogue,
//   la recherche, les fiches et la lecture. hentaivost.fr n'est essayé
//   qu'en secours pour la fiche et le lecteur si le pont échoue.
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
                setText(currentBridge()); hint = "https://hentai-fap.fr (moteur du catalogue)"
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
                .setMessage(
                    "Le catalogue et les lecteurs sont servis par le second champ (réseau Hentai " +
                        "Paradise, sans défi Cloudflare). Le premier champ n'est utilisé qu'en secours."
                )
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

    // Sections servies par le pont hentai-fap.fr (réseau identique, pas de
    // défi Cloudflare) — plus aucun appel hentaivost.fr dans le catalogue.
    // 2 sections complémentaires de Hentai-Fap (VOSTFR/non censuré y sont
    // déjà) : moins de requêtes parallèles vers le même domaine Cloudflare.
    override val mainPage = mainPageOf(
        "tag:harem" to "🎀 Harem",
        "tag:inceste" to "👪 Inceste"
    )

    // -------------------------------------------------------------------------
    // Cartes du pont (hentai-fap.fr) : <a id="thumbs-…"
    //   class='vignFloatH tl'
    //   href='…/hentai-video/{slug}'> + img data-src + alt=TITRE
    // (⚠ sauts de ligne entre attributs — tolérance [\s\S] obligatoire)
    // + cartes Cactus (hentaivost.fr) en secours.
    // -------------------------------------------------------------------------
    private fun parseCards(html: String, baseUrl: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex(
            """id=["']thumbs-\d+["'][\s\S]{0,60}?class='vignFloatH tl'[\s\S]{0,300}?href='(?:https?://[^']*)/hentai-video/([a-z0-9-]+)'[\s\S]{0,900}?alt=["']([^"']+)["']"""
        ).findAll(html).forEach { m ->
            val (slug, title) = m.destructured
            if (title.isBlank()) return@forEach
            val imgTag = Regex("""<img[^>]*>""").find(m.groupValues[0])?.groupValues?.get(0).orEmpty()
            val img = Regex("""data-src='([^']+)'""").find(imgTag)?.groupValues?.get(1)
                ?: Regex("""data-src="([^"]+)"""").find(imgTag)?.groupValues?.get(1)
                ?: Regex("""\ssrc='([^']+)'""").find(imgTag)?.groupValues?.get(1)
            out[slug] = newMovieSearchResponse(title, "$baseUrl/hentai-video/$slug", TvType.NSFW) {
                this.posterUrl = img
            }
        }
        if (out.isNotEmpty()) return out.values.toList()
        // secours : cartes Cactus de hentaivost.fr (ancienne structure)
        Regex(
            """<div class="picture-content">\s*<a href="(?:https?://[^"]*?)/([a-z0-9%.-]{6,})/"[^>]*title="([^"]+)"""" +
                """"[\s\S]{0,500}?<img[^>]+src="([^"]+)""""
        ).findAll(html).forEach { m ->
            val (slug, title, img) = m.destructured
            if (title.isBlank()) return@forEach
            out[slug] = newMovieSearchResponse(title, "$baseUrl/hentai-video/$slug", TvType.NSFW) {
                this.posterUrl = img
            }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val bridge = currentBridge()
        val url = when {
            request.data.startsWith("cat:") -> "$bridge/hentai-streaming/${request.data.removePrefix("cat:")}/$page"
            request.data.startsWith("tag:") -> "$bridge/tags-video/${request.data.removePrefix("tag:")}/$page"
            else -> "$bridge/hentai-streaming/hentai-sous-titre-francais-vostfr/$page"
        }
        var items: List<SearchResponse> = emptyList()
        // 2 tentatives : les requêtes parallèles de la page d'accueil peuvent
        // être rate-limitées (403 Cloudflare) — la 2e passe 1,5 s plus tard.
        for (attempt in 1..2) {
            val html = httpGet(url, referer = "$bridge/")
            if (html != null) {
                items = parseCards(html, bridge)
                if (items.isNotEmpty()) break
            }
            if (attempt == 1) kotlinx.coroutines.delay(1500)
        }
        return newHomePageResponse(request, items, hasNext = items.size >= 12)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isEmpty()) return emptyList()
        val bridge = currentBridge()
        val html = runCatching {
            app.get(
                "$bridge/search/$q/1",
                headers = headers() + mapOf("Cookie" to "searchType=hentai"),
                interceptor = cfKiller
            ).text
        }.getOrNull() ?: return emptyList()
        return parseCards(html, bridge)
    }

    // -------------------------------------------------------------------------
    // Fiche — moteur fap en premier (slugs identiques), hentaivost en secours
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val slug = url.trimEnd('/').substringAfterLast('/')
        val bridge = currentBridge()

        // 1) fiche du pont (hentai-fap.fr) — pas de défi Cloudflare
        var html = httpGet("$bridge/hentai-video/$slug", referer = "$bridge/")

        // 2) secours : fiche native hentaivost.fr
        if (html == null || "stream_ep" !in html) {
            html = httpGet(url) ?: html
        }
        if (html == null) throw ErrorLoadingException()

        val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.trim()
            ?: Regex("""<title>([^<|–-]+)""").find(html)?.groupValues?.get(1)?.trim()
            ?: slug.replace('-', ' ')
        val poster = Regex("""<meta property="og:image" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta property="og:description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta name="description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        return newMovieLoadResponse(title, url, TvType.NSFW, "$bridge/hentai-video/$slug") {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // -------------------------------------------------------------------------
    // Lecture — chaîne VALIDÉE du pont : /hentai-video/{slug} → div#stream_ep
    // (data-v, data-f) → /ajax/csrfToken.php → /ajax/getlink.php → MP4 signé
    // (hentaivost.fr n'est essayé qu'en dernier secours)
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
                headers = headers(referer) + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                interceptor = cfKiller
            ).text
        }.getOrNull()?.let { Regex(""""token"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        val postHeaders = buildMap {
            putAll(headers(referer))
            put("X-Requested-With", "XMLHttpRequest")
            put("Accept", "application/json, text/javascript, */*; q=0.01")
            put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            if (token != null) put("X-CSRF-Token", token)
        }
        var signed: String? = null
        for (attempt in 1..2) {
            signed = runCatching {
                app.post(
                    "$base/ajax/getlink.php",
                    headers = postHeaders,
                    data = mapOf("file" to path),
                    interceptor = cfKiller
                ).text.trim()
            }.getOrNull()
            if (!signed.isNullOrEmpty() && signed.startsWith("/")) break
            if (attempt == 1) kotlinx.coroutines.delay(1200)
        }
        if (signed == null || signed.startsWith("{") || !signed.startsWith("/")) return null
        return "${ep.server}$signed"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val bridge = currentBridge()

        // 1) pont hentai-fap.fr : fiche + lecteur signé (chaîne validée)
        val fapHtml = httpGet(data.takeIf { "hentai-video/" in it } ?: data, referer = "$bridge/")
        if (fapHtml != null) {
            findStreamEp(fapHtml)?.let { ep ->
                val link = fapSignedLink(bridge, ep, data) ?: return@let
                callback(
                    ExtractorLink(
                        name, name, link, data,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
            // iframes du lecteur réseau (player?v=…)
            Regex("""<iframe[^>]*\ssrc="(https?://[^"]+)"""").findAll(fapHtml).forEach { m ->
                val u = m.groupValues[1]
                if ("player?v=" in u) {
                    runCatching {
                        loadExtractor(u, data, subtitleCallback) { l -> callback(l) }
                    }
                    return true
                }
            }
        }

        // 2) secours : lecteur directement dans la page hentaivost native
        val slug = data.trimEnd('/').substringAfterLast('/')
        val nativeHtml = httpGet("$mainUrl/$slug/")
        if (nativeHtml != null) {
            findStreamEp(nativeHtml)?.let { ep ->
                val link = fapSignedLink(bridge, ep, "$mainUrl/$slug/") ?: return@let
                callback(
                    ExtractorLink(
                        name, name, link, "$mainUrl/$slug/",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
        }

        // 3) dernier recours : fiche fap par slug
        val fapSlugHtml = httpGet("$bridge/hentai-video/$slug", referer = "$bridge/")
        val ep = fapSlugHtml?.let { findStreamEp(it) } ?: return false
        val link = fapSignedLink(bridge, ep, "$bridge/hentai-video/$slug") ?: return false
        callback(
            ExtractorLink(
                name, name, link, data,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
