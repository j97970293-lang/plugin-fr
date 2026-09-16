package com.hentaifap

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

// ===========================================================================
// Hentai-Fap — hentai VOSTFR / VOSTA / RAW / non censuré (MP4 directs signés)
// ===========================================================================
// Site : https://hentai-fap.fr (réseau « Hentai Paradise » — c'est lui qui
// sert la section streaming de hentai-paradise.fr, qui y redirige).
//   Listes    : /hentai-streaming/{catégorie}/{page} (12 cartes/page,
//               226 pages) — cartes : <a id="thumbs-…"
//               class='vignFloatH tl' href=…/hentai-video/{slug}> + img
//               data-src (lazy) + alt=TITRE
//   Recherche : /search/{q}/{page} avec cookie searchType=hentai
//   Fiche     : /hentai-video/{slug} → div#stream_ep avec
//               data-v (fichier), data-f (dossier), data-type
//               (clip → chemin sans /movies/), data-server (défaut
//               https://video.hentai-manga.io)
//   Lecture   : GET /ajax/csrfToken.php (X-Requested-With) → {token} ;
//               POST /ajax/getlink.php (file=/movies/{f}/{v}, en-têtes
//               X-CSRF-Token + X-Requested-With) → chemin signé nginx
//               (?st=…&e=…) → {server}{chemin} = MP4 direct (206 vérifié).
// ===========================================================================
@CloudstreamPlugin
class HentaiFapPlugin : Plugin() {
    override fun load(context: Context) {
        HentaiFapProvider.appContext = context.applicationContext
        registerMainAPI(HentaiFapProvider())
        openSettings = { ctx -> HentaiFapProvider.showSettings(ctx) }
    }
}

class HentaiFapProvider : MainAPI() {
    companion object {
        const val DEFAULT_URL = "https://hentai-fap.fr"
        const val DEFAULT_CDN = "https://video.hentai-manga.io"

        @Volatile
        var appContext: Context? = null

        private const val PREFS_NAME = "hentaifap_settings"
        private const val PREF_URL = "site_url"

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
                setText(currentUrl()); hint = "https://hentai-fap.fr"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de Hentai-Fap")
                .setMessage("Si le site change de domaine, indiquez l'adresse actuelle (réseau Hentai Paradise).")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setSiteUrl(context, DEFAULT_URL) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "Hentai-Fap"
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

    // 3 sections seulement : la page d'accueil charge toutes les sections en
    // parallèle et hentai-fap.fr (Cloudflare) rate-limite au-delà — les
    // sections RAW/VOSTES restent accessibles via la recherche.
    override val mainPage = mainPageOf(
        "hentai-sous-titre-francais-vostfr" to "🇫🇷 VOSTFR",
        "hentai-non-censure" to "🔓 Non censuré",
        "hentai-sous-titre-anglais-vosta" to "🇬🇧 VOSTA"
    )

    // -------------------------------------------------------------------------
    // Cartes : <a id="thumbs-…"
    //            class='vignFloatH tl'
    //            href='…/hentai-video/{slug}'> + <img … data-src=… alt=TITRE>
    // (guillemets mixtes : id en doubles, class/href en simples ;
    //  ⚠ sauts de ligne entre attributs — tolérance [\s\S] OBLIGATOIRE)
    // -------------------------------------------------------------------------
    private fun parseCards(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex("""id=["']thumbs-\d+["'][\s\S]{0,60}?class='vignFloatH tl'[\s\S]{0,300}?href='(?:https?://[^']*)/hentai-video/([a-z0-9-]+)'[\s\S]{0,900}?alt=["']([^"']+)["']""")
            .findAll(html).forEach { m ->
                val (slug, title) = m.destructured
                if (title.isBlank()) return@forEach
                val imgTag = Regex("""<img[^>]*>""").find(m.groupValues[0])?.groupValues?.get(0).orEmpty()
                val img = Regex("""data-src='([^']+)'""").find(imgTag)?.groupValues?.get(1)
                    ?: Regex("""data-src="([^"]+)"""").find(imgTag)?.groupValues?.get(1)
                    ?: Regex("""\ssrc='([^']+)'""").find(imgTag)?.groupValues?.get(1)
                out[slug] = newMovieSearchResponse(title, "$mainUrl/hentai-video/$slug", TvType.NSFW) {
                    this.posterUrl = img
                }
            }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        var items: List<SearchResponse> = emptyList()
        // 2 tentatives : les requêtes parallèles de la page d'accueil peuvent
        // être rate-limitées (403 Cloudflare) — la 2e passe 1,5 s plus tard.
        for (attempt in 1..2) {
            val html = httpGet("$mainUrl/hentai-streaming/${request.data}/$page")
            if (html != null) {
                items = parseCards(html)
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
        val html = runCatching {
            app.get(
                "$mainUrl/search/$q/1",
                headers = headers() + mapOf("Cookie" to "searchType=hentai"),
                interceptor = cfKiller
            ).text
        }.getOrNull() ?: return emptyList()
        return parseCards(html)
    }

    // -------------------------------------------------------------------------
    // Fiche : #stream_ep (data-v, data-f, data-type, data-server)
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val html = httpGet(url) ?: throw ErrorLoadingException()
        val slug = url.trimEnd('/').substringAfterLast('/')
        val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.trim()
            ?: Regex("""<title>([^<|]+)""").find(html)?.groupValues?.get(1)?.trim()
            ?: slug.replace('-', ' ')
        val poster = Regex("""<meta property="og:image" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-cover="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta property="og:description" content="([^"]*)"""").find(html)?.groupValues?.get(1)

        // durée + tags pour l'affichage
        val duration = Regex(""""duration"\s*:\s*"?PT(\d+)M""").find(html)?.groupValues?.get(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.duration = duration?.times(60)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val html = httpGet(data) ?: return false
        val ep = Regex("""<div id="stream_ep"[^>]*>""").find(html)?.groupValues?.get(0) ?: return false
        val v = Regex("""data-v="([^"]+)"""").find(ep)?.groupValues?.get(1) ?: return false
        val f = Regex("""data-f="([^"]+)"""").find(ep)?.groupValues?.get(1) ?: return false
        val type = Regex("""data-type="([^"]*)"""").find(ep)?.groupValues?.get(1) ?: "video"
        val server = Regex("""data-server="([^"]+)"""").find(ep)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
            ?: DEFAULT_CDN
        val path = if (type == "clip") "/$f/$v" else "/movies/$f/$v"

        // 1) jeton CSRF
        val token = runCatching {
            app.get(
                "$mainUrl/ajax/csrfToken.php",
                headers = headers(data) + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                interceptor = cfKiller
            ).text
        }.getOrNull()?.let { runCatching { AppUtils_tok.parse(it) }.getOrNull() }

        // 2) POST getlink.php → chemin signé
        val postHeaders = buildMap {
            putAll(headers(data))
            put("X-Requested-With", "XMLHttpRequest")
            put("Accept", "application/json, text/javascript, */*; q=0.01")
            put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            if (token != null) put("X-CSRF-Token", token)
        }
        var signed: String? = null
        for (attempt in 1..2) {
            signed = runCatching {
                app.post(
                    "$mainUrl/ajax/getlink.php",
                    headers = postHeaders,
                    data = mapOf("file" to path),
                    interceptor = cfKiller
                ).text.trim()
            }.getOrNull()
            if (!signed.isNullOrEmpty() && signed.startsWith("/")) break
            if (attempt == 1) kotlinx.coroutines.delay(1200)
        }
        if (signed == null || signed.startsWith("{") || !signed.startsWith("/")) return false

        val link = "$server$signed"
        if (!link.startsWith("http")) return false
        callback(
            ExtractorLink(
                name, name, link, data,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }

    /** Parse minimal du JSON {"token":"…"} sans DTO dédié. */
    private object AppUtils_tok {
        fun parse(text: String): String? =
            Regex(""""token"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
    }
}
