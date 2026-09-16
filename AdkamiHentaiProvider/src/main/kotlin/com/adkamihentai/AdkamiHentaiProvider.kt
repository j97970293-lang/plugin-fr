package com.adkamihentai

import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

// ===========================================================================
// AdKami Hentai — hentai VOSTFR / raw (français)
// ===========================================================================
// Site : https://hentai.adkami.com (cookie nsfw=true pour confirmer les 18+).
//   Accueil    : /hentai-streaming → h-cards (Nouveautés)
//   Catalogue  : /video?t=4&page={n} → video-item-list (200 par page)
//   Recherche  : POST /api/search/hentai (form: query=… → h-cards)
//   Fiche      : /hentai/{id} → poster (image.adkami.com/cover/250/{id}.jpg),
//                synopsis (og:description), note (itemprop ratingValue) et
//                liste d'épisodes dans #nav-episode :
//                <li class="saison">saison N</li>
//                <li><a href="/hentai/{id}/{ep}/{saison}/{lang}/{team}/">LIBELLÉ</a></li>
//                (lang : 2 = vostfr, 4 = raw)
//   Épisode    : la zone player (.video-video iframe) n'est rendue qu'aux IP
//                « propres » (mobiles) : les iframes portent un data-src
//                obfusqué « https://www.youtube.com/embed/{base64} » décodé
//                par XOR avec la clé « ETEfazefzeaZa13MnZEe » (main.min.js) ;
//                l'URL obtenue est un hébergeur classique (streamtape, dood,
//                sibnet…) résolu par les extracteurs intégrés de CloudStream.
// ===========================================================================
@CloudstreamPlugin
class AdkamiHentaiPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        AdkamiHentaiProvider.appContext = context.applicationContext
        registerMainAPI(AdkamiHentaiProvider())
        openSettings = { ctx -> AdkamiHentaiProvider.showSettings(ctx) }
    }
}

class AdkamiHentaiProvider : MainAPI() {
    companion object {
        const val DEFAULT_URL = "https://hentai.adkami.com"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "adkami_hentai_settings"
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
                setText(currentUrl()); hint = "https://hentai.adkami.com"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse d'AdKami Hentai")
                .setMessage("Si le site change de domaine, indiquez l'adresse actuelle.")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setSiteUrl(context, DEFAULT_URL) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "AdKami Hentai"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.NSFW)

    // Le site met un avertissement NSFW piloté par le cookie « nsfw » ;
    // on le pose d'office (les pages listes sont publiques).
    private val cfKiller by lazy { CloudflareKiller() }

    private fun headers() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9",
        "Cookie" to "nsfw=true"
    )

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    override val mainPage = mainPageOf(
        "new" to "🆕 Nouveautés",
        "catalog" to "📚 Catalogue A-Z"
    )

    // -------------------------------------------------------------------------
    // Listes
    // -------------------------------------------------------------------------
    /** h-cards de /hentai-streaming et de la recherche : a[href=/hentai/{id}…] + img + h4.title */
    /** h-cards (/hentai-streaming + recherche) : a[href=/hentai/{id}…] + img + h4.title */
    private fun parseHcards(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex("""<a href="(?:https?://[^"]*?/hentai/(\d+))(?:/[^"]*)?"[^>]*>[\s\S]{0,700}?<img[^>]+src="([^"]+)"[\s\S]{0,700}?<h4 class="title">([^<]+)</h4>""")
            .findAll(html).forEach { m ->
                val (id, img, title) = m.destructured
                out[id] = newMovieSearchResponse(title.trim(), "$mainUrl/hentai/$id", TvType.NSFW) {
                    this.posterUrl = img
                }
            }
        return out.values.toList()
    }

    /** video-item-list du catalogue : a[href] + img data-original + span.title */
    private fun parseListItems(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex("""<a href="https?://[^"]*?/hentai/(\d+)">\s*<img[^>]+data-original="([^"]+)"[\s\S]{0,600}?<span class="title">([^<]+)</span>""")
            .findAll(html).forEach { m ->
                val (id, img, title) = m.destructured
                out[id] = newMovieSearchResponse(
                    title.trim().ifBlank { "Hentai #$id" },
                    "$mainUrl/hentai/$id", TvType.NSFW
                ) {
                    this.posterUrl = img
                }
            }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val html = when (request.data) {
            "new" -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                runCatching { app.get("$mainUrl/hentai-streaming", headers = headers(), interceptor = cfKiller).text }.getOrNull()
            }
            else -> runCatching {
                app.get("$mainUrl/video?t=4&page=$page", headers = headers(), interceptor = cfKiller).text
            }.getOrNull()
        } ?: return newHomePageResponse(request, emptyList(), false)
        val items = if (request.data == "new") parseHcards(html) else parseListItems(html)
        return newHomePageResponse(request, items, hasNext = request.data != "new" && items.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Recherche — POST /api/search/hentai (query=…)
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val html = runCatching {
            app.get("$mainUrl/") // referer de base
            app.post(
                "$mainUrl/api/search/hentai",
                headers = headers() + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                data = mapOf("query" to q)
            ).text
        }.getOrNull() ?: return emptyList()
        return parseHcards(html)
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val id = url.trimEnd('/').substringAfterLast('/')
        val html = runCatching {
            app.get("$mainUrl/hentai/$id", headers = headers(), interceptor = cfKiller).text
        }.getOrNull() ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")

        // titre : alt du poster dans #nav-episode, sinon h1 amputé de l'épisode
        val title = Regex("""<img[^>]+src="https://image\.adkami\.com/cover/250/$id\.jpg[^"]*"[^>]*alt="([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.trim()
            ?: Regex("""<h1[^>]*>([^<]+)</h1>""").find(html)?.groupValues?.get(1)?.substringBefore(" - ")?.trim()
            ?: "Hentai #$id"
        val poster = "https://image.adkami.com/cover/250/$id.jpg"
        val plot = Regex("""<meta property="og:description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        val score = Regex("""itemprop="ratingValue"[^>]*>([\d.,]+)<""").find(html)?.groupValues?.get(1)
            ?.replace(",", ".")?.toDoubleOrNull()
        // tags : liens /hentai?… ou textes d'info — on garde les blocs « genre » si présents
        val year = Regex("""Première parution en (\d{4})""").find(html)?.groupValues?.get(1)?.toIntOrNull()

        // épisodes : #nav-episode → li.saison + li>a (une entrée par langue/épisode)
        val episodes = mutableListOf<Episode>()
        var season = 1
        val navArea = Regex("""id="nav-episode"[\s\S]*?</div>""").find(html)?.value ?: html
        Regex("""<li class="saison"[^>]*>\s*saison (\d+)|<li[^>]*><a href="([^"]+/hentai/$id/(\d+)/(\d+)/(\d+)/(\d+)/?)"[^>]*>([^<]+)</a>""")
            .findAll(navArea).forEach { m ->
                if (m.groupValues[1].isNotBlank()) {
                    season = m.groupValues[1].toIntOrNull() ?: season
                } else if (m.groupValues[2].isNotBlank()) {
                    val epNum = m.groupValues[3].toIntOrNull() ?: (episodes.size + 1)
                    episodes += newEpisode(m.groupValues[2]) {
                        this.name = m.groupValues[7].trim()
                        this.season = season
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                }
            }
        if (episodes.isEmpty()) {
            throw com.lagradost.cloudstream3.ErrorLoadingException("Aucun épisode trouvé")
        }

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score?.let { com.lagradost.cloudstream3.Score.from10(it) }
        }
    }

    // -------------------------------------------------------------------------
    // Lecteurs — .video-video iframe[data-src] décodé (base64 + XOR)
    // -------------------------------------------------------------------------
    /**
     * Décodeur d'adkami (main.min.js) : data-src =
     * "https://www.youtube.com/embed/{base64}" ; le base64 décode en octets,
     * chaque octet donne (175 XOR octet) − clé[i] (clé = ETEfazefzeaZa13MnZEe,
     * cyclique) → caractère de l'URL réelle de l'hébergeur.
     */
    private fun decodeAdkamiEmbed(dataSrc: String): String? {
        val marker = "https://www.youtube.com/embed/"
        val i = dataSrc.indexOf(marker)
        if (i < 0) return null
        val b64 = dataSrc.substring(i + marker.length).substringBefore('"').trim()
        return runCatching {
            val bytes = java.util.Base64.getMimeDecoder().decode(b64)
            val key = "ETEfazefzeaZa13MnZEe"
            val sb = StringBuilder()
            var k = 0
            for (b in bytes) {
                sb.append((((175 xor (b.toInt() and 0xFF)) - key[k].code) and 0xFFFF).toChar())
                k = if (k > key.length - 2) 0 else k + 1
            }
            sb.toString().takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val html = runCatching {
            app.get(data, headers = headers(), interceptor = cfKiller).text
        }.getOrNull() ?: return false

        var found = false
        // Lecteurs rendus côté serveur (cf. main.min.js) :
        //  - <iframe data-src="…"> ou data-litespeed-src : URL directe
        //    (ex. //www.dailymotion.com/embed/video/…) OU token chiffré
        //    « https://www.youtube.com/embed/{b64} » ;
        //  - conteneurs avec data-url="…{token}…" (même chiffrement).
        val embeds = LinkedHashSet<String>()
        fun addCandidate(v: String?) {
            if (v.isNullOrBlank()) return
            // token chiffré → décodage, sinon URL directe
            decodeAdkamiEmbed(v)?.let { embeds.add(it) } ?: run {
                if (v.startsWith("http") || v.startsWith("//")) {
                    if ("doubleclick" !in v && "googlesyndication" !in v) embeds.add(v)
                }
            }
        }
        Regex("""<iframe[^>]*data-litespeed-src="([^"]+)"[^>]*>""").findAll(html).forEach { addCandidate(it.groupValues[1]) }
        Regex("""<iframe[^>]*\sdata-src="([^"]+)"[^>]*>""").findAll(html).forEach { addCandidate(it.groupValues[1]) }
        Regex("""<iframe[^>]*\ssrc="([^"]+)"[^>]*>""").findAll(html).forEach {
            val u = it.groupValues[1]
            if (u.startsWith("http") && "youtube.com" !in u && "doubleclick" !in u && "googlesyndication" !in u) embeds.add(u)
        }
        Regex("""\sdata-url="(https://www\.youtube\.com/embed/[^"]+)"""").findAll(html).forEach { addCandidate(it.groupValues[1]) }
        embeds.forEach { u ->
            runCatching {
                loadExtractor(if (u.startsWith("//")) "https:$u" else u, mainUrl, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            }
        }
        return found
    }
}
