package com.zenix

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Entry point of the plugin, this is the class that CloudStream loads.
 */
@CloudstreamPlugin
class ZenixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ZenixProvider())
        // Custom extractor for 1embed.cc (direct HLS playlists in the page)
        registerExtractorAPI(OneEmbed())
    }
}

/**
 * 1embed.cc — embed player whose page embeds a Next.js payload listing its
 * servers as JSON: `{"name":"Solari","url":"/v/solari_<id>.m3u8","type":"hls"}`.
 * The playlists are served directly (no referer needed, verified).
 */
class OneEmbed : ExtractorApi() {
    override val name = "1Embed"
    override val mainUrl = "https://1embed.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, headers = baseHeaders).text
        // Un-escape the Next.js flight payload (\" -> ")
        val norm = html.replace("\\\"", "\"")
        val servers = LinkedHashMap<String, String>()
        Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+\.m3u8)"""")
            .findAll(norm).forEach { servers[it.groupValues[2]] = it.groupValues[1] }
        // Fallback: any /v/*.m3u8 playlist path on the page
        if (servers.isEmpty()) {
            Regex(""""(/v/[a-zA-Z0-9_.-]+\.m3u8)"""").findAll(norm).forEach {
                if (it.groupValues[1] !in servers) servers[it.groupValues[1]] = "Serveur"
            }
        }
        servers.forEach { (path, serverName) ->
            val absolute = if (path.startsWith("http")) path else "$mainUrl$path"
            callback(
                newExtractorLink(name, "$name · $serverName", absolute) {
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.M3U8
                }
            )
        }
    }

    companion object {
        private val baseHeaders = mapOf("user-agent" to USER_AGENT)
    }
}

/**
 * Extension CloudStream pour https://zenix.best (films & séries FR, rendu serveur PHP).
 *
 * Structure du site (vérifiée) :
 *   /trending /top-imdb /movies /tv-shows /genre/{slug}   listes (1re page, pagination en JS)
 *   /movie/{slug} /tv-show/{slug} /episode/{slug}/{s}-{e} fiches
 *   /ajax/search/suggest?q=…                              recherche JSON (la route /search/{q}
 *                                                          ne filtre PAS côté serveur — ignorée)
 *
 * Lecture — la page film/épisode contient des boutons selectStream(n,'url','Libellé | LANGUE','type')
 * listant ~24 serveurs. La plupart sont des embeds JS sans extracteur CloudStream, aussi
 * l'extension interroge en parallèle plusieurs agrégateurs fondés sur l'ID TMDB de la fiche :
 *   - 1embed.cc        → playlists HLS directes (extracteur maison, fiable)
 *   - apis.wavewatch.top/apiwiflix.php   → liens hébergeurs avec langue (VF/VOSTFR), films & séries
 *   - apis.wavewatch.top/playerix.php    → liens hébergeurs (base64), films & séries
 *   - api.movix.cash/api/tmdb/…          → liens hébergeurs FR (qualité indiquée)
 *   - api.movix.cash/api/fstream/…       → liens french-stream avec vraies étiquettes VFQ/VF/VOSTFR (films)
 *   - primesrc.me/api/v1/…               → ~34 serveurs (Filemoon, Dood…) via clés (protégé CF : best effort)
 * Tous les liens finaux sont étiquetés avec leur langue (« Filemoon · VF », « Vidzy · VOSTFR »…).
 */
class ZenixProvider : MainAPI() {

    override var mainUrl = "https://zenix.best" // domaine de secours officiel : zenix.lol
    override var name = "Zenix"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "🔥 Tendances",
        "$mainUrl/top-imdb" to "⭐ Top IMDb",
        "$mainUrl/movies" to "🎬 Films",
        "$mainUrl/tv-shows" to "📺 Séries",
        "$mainUrl/genre/action" to "💥 Action",
        "$mainUrl/genre/adventure" to "🧭 Aventure",
        "$mainUrl/genre/animation" to "✨ Animation",
        "$mainUrl/genre/comedy" to "😂 Comédie",
        "$mainUrl/genre/science-fiction" to "🚀 Science-Fiction",
        "$mainUrl/genre/horror" to "👻 Horreur",
        "$mainUrl/genre/thriller" to "🔪 Thriller",
        "$mainUrl/genre/romance" to "❤️ Romance",
        "$mainUrl/genre/crime" to "🕵️ Policier",
        "$mainUrl/genre/drama" to "🎭 Drame",
    )

    private val baseHeaders = mapOf("user-agent" to USER_AGENT)
    private val cfKiller by lazy { CloudflareKiller() }

    /** Un lien d'hébergeur à passer aux extracteurs, avec sa langue quand elle est connue. */
    private data class HostLink(
        val url: String,
        val lang: String? = null,
        val label: String,   // utilisé si l'extraction générique produit le lien
        val origin: String,  // d'où vient le lien (tri)
        val direct: Boolean = false // playlist HLS directement jouable
    )

    // -------------------------------------------------------------------------
    // Page d'accueil
    // -------------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val doc = app.get(request.data, headers = baseHeaders).document
        val cards = doc.select("a.zenix-card__inner")
            .mapNotNull { parseCard(it) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, cards)
    }

    // -------------------------------------------------------------------------
    // Recherche : API JSON /ajax/search/suggest.
    // La route HTML /search/{q} ne filtre pas côté serveur (elle renvoie toujours
    // les derniers ajouts) — on ne l'utilise PAS.
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        return runCatching {
            val json = app.get(
                "$mainUrl/ajax/search/suggest",
                params = mapOf("q" to trimmed),
                headers = baseHeaders
            ).text
            AppUtils.tryParseJson<ZenixSuggestResponse>(json)?.posts
                ?.filter { !it.url.isNullOrBlank() && !it.title.isNullOrBlank() }
                ?.mapNotNull { p ->
                    val url = fixUrl(p.url!!)
                    val isTv = p.type == "tv"
                    val poster = p.image?.takeIf { it.startsWith("http") }
                    val year = p.year?.trim()?.toIntOrNull()
                    if (isTv) {
                        newTvSeriesSearchResponse(p.title!!, url, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    } else {
                        newMovieSearchResponse(p.title!!, url, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    // -------------------------------------------------------------------------
    // Détail : /movie/{slug}, /tv-show/{slug} (une page /episode/ remonte à la série)
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        var fetchUrl = url
        var doc = app.get(fetchUrl, headers = baseHeaders).document

        // Une page épisode renvoie toujours vers sa série
        if ("/episode/" in fetchUrl) {
            doc.selectFirst("a[href*='/tv-show/']")?.attr("href")?.let {
                fetchUrl = fixUrl(it)
                doc = app.get(fetchUrl, headers = baseHeaders).document
            }
        }
        val isTv = "/tv-show/" in fetchUrl

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("–")?.trim()
            ?: "Inconnu"

        val poster = fixUrlNull(
            doc.selectFirst(".zx-poster-wrap img")?.attr("src")?.takeIf { it.startsWith("http") }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
        )

        val plot = doc.selectFirst("[class*=overview]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Badges de la fiche : note (or), année / durée / genres (gris) ;
        // on ignore les badges « vues » et le lien IMDb.
        var rating: Double? = null
        var year: Int? = null
        val genres = mutableListOf<String>()
        doc.select(".zx-meta .zx-badge").forEach { badge ->
            val text = badge.text().replace('\u00A0', ' ').trim()
            if (text.isBlank()) return@forEach
            val cls = badge.className()
            when {
                "zx-badge-gold" in cls ->
                    rating = text.replace(",", ".").toDoubleOrNull()
                "zx-badge-views" in cls || "zx-badge-imdb" in cls -> { /* ignoré */ }
                text.endsWith("min") -> { /* durée : ignorée */ }
                text.toIntOrNull() != null ->
                    if (year == null) year = text.toInt()
                else -> genres.add(text)
            }
        }

        return if (isTv) {
            newTvSeriesLoadResponse(title, fetchUrl, TvType.TvSeries, parseEpisodes(doc)) {
                this.posterUrl = poster
                this.plot = plot
                this.score = rating?.let { Score.from10(it) }
                this.year = year
                this.tags = genres.distinct()
            }
        } else {
            newMovieLoadResponse(title, fetchUrl, TvType.Movie, fetchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.score = rating?.let { Score.from10(it) }
                this.year = year
                this.tags = genres.distinct()
            }
        }
    }

    /** Épisodes d'une série : liens /episode/{slug}/{saison}-{épisode} (toutes saisons). */
    private fun parseEpisodes(doc: Document): List<Episode> {
        return doc.select("a[href*='/episode/']")
            .mapNotNull { a ->
                val href = a.attr("href")
                val m = episodeRegex.find(href) ?: return@mapNotNull null
                val epNumber = m.groupValues[2].toIntOrNull()
                // Certains titres sont des noms de fichiers (« Reacher-S04-E05.mp4 »)
                val name = a.selectFirst("h3")?.text()?.trim()?.takeIf {
                    it.isNotBlank() && !Regex("""\.(mp4|mkv|avi)$""", RegexOption.IGNORE_CASE).containsMatchIn(it)
                } ?: "Épisode ${epNumber ?: ""}".trim()
                val poster = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
                newEpisode(fixUrl(href)) {
                    this.name = name
                    this.season = m.groupValues[1].toIntOrNull()
                    this.episode = epNumber
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    // -------------------------------------------------------------------------
    // Lecture
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val raw = app.get(data, headers = baseHeaders).text
        val isTv = "/tv-show/" in data || "/episode/" in data
        val epMatch = episodeRegex.find(data)
        val season = epMatch?.groupValues?.get(1)?.toIntOrNull()
        val episode = epMatch?.groupValues?.get(2)?.toIntOrNull()
        // L'ID TMDB de la fiche se trouve dans l'URL du lecteur interne Zenix
        val tmdb = Regex("""[?&]tmdb=(\d+)""").find(raw)?.groupValues?.get(1)

        var found = false
        val lock = Any()
        fun emit(link: ExtractorLink) = synchronized(lock) {
            found = true
            callback(link)
        }

        // Les liens d'hébergeurs collectés (dédupliqués par URL)
        val hostLinks = LinkedHashMap<String, HostLink>()

        suspend fun addHostLinks(links: List<HostLink>) {
            synchronized(hostLinks) { links.forEach { if (it.url.startsWith("http")) hostLinks.putIfAbsent(it.url, it) } }
        }

        coroutineScope {
            // ---- 1embed : HLS directs, fiables, émis immédiatement ----
            if (tmdb != null) {
                val oneEmbedUrl = if (epMatch != null) {
                    "https://1embed.cc/embed/tv/$tmdb/${epMatch.groupValues[1]}/${epMatch.groupValues[2]}"
                } else if (isTv) {
                    "https://1embed.cc/embed/tv/$tmdb/1/1"
                } else {
                    "https://1embed.cc/embed/movie/$tmdb"
                }
                async(Dispatchers.IO) {
                    runCatching { OneEmbed().getUrl(oneEmbedUrl, mainUrl, subtitleCallback) { emit(it) } }
                }
            }

            // ---- Agrégateurs (en parallèle) ----
            if (tmdb != null) {
                val aggregators = listOf(
                    async(Dispatchers.IO) { runCatching { apiwiflixLinks(tmdb, season, episode) }.getOrDefault(emptyList()) },
                    async(Dispatchers.IO) { runCatching { playerixLinks(tmdb, season, episode) }.getOrDefault(emptyList()) },
                    async(Dispatchers.IO) { runCatching { movixLinks(tmdb, season, episode) }.getOrDefault(emptyList()) },
                    async(Dispatchers.IO) { runCatching { movixFstreamLinks(tmdb, season, episode) }.getOrDefault(emptyList()) },
                    async(Dispatchers.IO) { runCatching { primesrcLinks(tmdb, season, episode) }.getOrDefault(emptyList()) }
                )
                aggregators.awaitAll().forEach { addHostLinks(it) }
            }
        }

        // ---- Boutons « Serveurs de lecture » de la page (labels avec langue) ----
        val buttonLinks = mutableListOf<HostLink>()
        selectStreamRegex.findAll(raw).forEach { m ->
            val url = jsUnescape(m.groupValues[1])
            val label = m.groupValues[2]
            if (url.startsWith("http")) {
                val lang = Regex("""\|\s*(VF|VOSTFR|VO|VF/VO|MULTI)\s*$""").find(label)?.groupValues?.get(1)
                buttonLinks += HostLink(url, lang, label, "zenix")
            }
        }
        addHostLinks(buttonLinks)

        // ---- Extraction parallèle (VF d'abord, puis VOSTFR, puis le reste) ----
        fun priority(hl: HostLink): Int {
            val langP = when (hl.lang) {
                "VF", "VFQ", "VFF", "VF/VO" -> 0
                "VOSTFR" -> 1
                null -> 2
                else -> 3
            }
            val originP = when (hl.origin) {
                "apiwiflix" -> 0; "movix" -> 1; "fstream" -> 2; "playerix" -> 3
                "primesrc" -> 4; "zenix" -> 5; else -> 6
            }
            return langP * 10 + originP
        }

        val ordered = synchronized(hostLinks) { hostLinks.values.toList() }
            .sortedWith(compareBy(::priority))

        // ---- Playlists HLS directes : émises immédiatement (aucune extraction nécessaire) ----
        ordered.filter { it.direct }.forEach { hl ->
            val name = "Zenix · ${hl.label}" + (hl.lang?.let { " · $it" } ?: "")
            emit(
                newExtractorLink(name, name, hl.url) {
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.M3U8
                }
            )
        }

        val toExtract = ordered.filter { !it.direct }
        if (toExtract.isNotEmpty()) {
            val semaphore = Semaphore(6)
            coroutineScope {
                toExtract.map { hl ->
                    async(Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            var produced = 0
                            // 1embed : extraction directe (extracteur maison)
                            if ("1embed.cc" in hl.url) {
                                runCatching {
                                    OneEmbed().getUrl(hl.url, mainUrl, subtitleCallback) {
                                        produced++; emit(relabel(it, hl.lang))
                                    }
                                }
                            } else {
                                runCatching {
                                    loadExtractor(hl.url, mainUrl, { sub ->
                                        synchronized(lock) { subtitleCallback(sub) }
                                    }, { link ->
                                        produced++
                                        emit(relabel(link, hl.lang))
                                    })
                                }
                            }
                            // Secours générique si l'extracteur n'a rien donné
                            if (produced == 0 && "zenix.best" !in hl.url) {
                                runCatching {
                                    genericExtract(
                                        hl.url, hl.label, hl.lang,
                                        { sub -> synchronized(lock) { subtitleCallback(sub) } },
                                        { link -> emit(relabel(link, hl.lang)) }
                                    )
                                }
                            }
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }
        }

        return found
    }

    /** Ré-étiquette un lien avec sa langue (« Filemoon · VF ») pour l'utilisateur. */
    private fun relabel(link: ExtractorLink, lang: String?): ExtractorLink {
        if (lang.isNullOrBlank()) return link
        return ExtractorLink(
            link.source,
            "${link.name} · $lang",
            link.url,
            link.referer,
            link.quality,
            link.headers,
            link.extractorData,
            link.type,
            link.audioTracks
        )
    }

    // -------------------------------------------------------------------------
    // Agrégateurs — tous fondés sur l'ID TMDB, testés sur films et épisodes
    // -------------------------------------------------------------------------

    /** apis.wavewatch.top/apiwiflix.php — liens hébergeurs AVEC langue (VF/VOSTFR). */
    private suspend fun apiwiflixLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = if (season != null && episode != null) {
            "https://apis.wavewatch.top/apiwiflix.php?id=$tmdb&season=$season&episode=$episode"
        } else {
            "https://apis.wavewatch.top/apiwiflix.php?id=$tmdb"
        }
        val html = app.get(url, headers = baseHeaders).text
        val m = Regex("""allSources\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        val sources = AppUtils.tryParseJson<Array<ApiWiflixSource>>(m.groupValues[1]) ?: return emptyList()
        return sources.filter { !it.url.isNullOrBlank() }.map {
            HostLink(it.url!!, it.language?.takeIf { l -> l.isNotBlank() }, it.name ?: "Lecteur", "apiwiflix")
        }
    }

    /** apis.wavewatch.top/playerix.php — boutons data-url avec langue (VF/VOSTFR/MULTI),
     *  liens hébergeurs encodés en base64 (u=…) et parfois des m3u8 directs. */
    private suspend fun playerixLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = if (season != null && episode != null) {
            // NB : « saison= » est ignoré par l'API — les paramètres anglais fonctionnent
            "https://apis.wavewatch.top/playerix.php?type=tv&id=$tmdb&season=$season&episode=$episode"
        } else {
            "https://apis.wavewatch.top/playerix.php?type=movie&id=$tmdb"
        }
        val html = app.get(url, headers = baseHeaders).text
        val out = mutableListOf<HostLink>()
        val seenHosts = mutableSetOf<String>()
        Regex("""<button([^>]*data-url[^>]*)>(.*?)</button>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val attrs = jsUnescape(m.groupValues[1])
                val content = m.groupValues[2]
                val dataUrl = Regex("""data-url="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
                val fmt = Regex("""data-fmt="([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: "iframe"
                if (Regex("""data-alive="0"""").containsMatchIn(attrs)) return@forEach
                val lang = Regex("""class="lang"[^>]*>\s*([^<]+)""").find(content)?.groupValues?.get(1)
                    ?.trim()?.split(" ")?.lastOrNull() // « 🇫🇷 VF » -> VF
                    ?.takeIf { it.isNotBlank() && it != "?" }
                val b64 = Regex("""u=([A-Za-z0-9+/=_-]{16,})""").find(dataUrl)?.groupValues?.get(1) ?: return@forEach
                val decoded = decodeB64(b64) ?: return@forEach
                if (!decoded.startsWith("http")) return@forEach
                val host = Regex("""https?://([^/]+)""").find(decoded)?.groupValues?.get(1) ?: "Lecteur"

                if (fmt == "m3u8") {
                    // Playlist HLS directe — jouable telle quelle
                    out += HostLink(decoded, lang ?: "MULTI", host, "playerix-hls", direct = true)
                } else {
                    // Lien d'hébergeur : un seul par (hôte, langue) pour éviter 17 miroirs identiques
                    val key = "$host|${lang ?: "?"}"
                    if (key in seenHosts) return@forEach
                    seenHosts += key
                    out += HostLink(decoded, lang, host, "playerix")
                }
            }
        return out.take(40)
    }

    private fun decodeB64(s: String): String? = runCatching {
        String(Base64.decode(s, Base64.URL_SAFE))
    }.getOrNull() ?: runCatching {
        String(Base64.decode(s, Base64.DEFAULT))
    }.getOrNull()

    /** api.movix.cash/api/tmdb/… — liens hébergeurs FR avec qualité. */
    private suspend fun movixLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = if (season != null && episode != null) {
            "https://api.movix.cash/api/tmdb/tv/$tmdb?season=$season&episode=$episode"
        } else {
            "https://api.movix.cash/api/tmdb/movie/$tmdb"
        }
        val json = app.get(url, headers = baseHeaders).text
        val root = AppUtils.parseJson<MovixResponse>(json)
        val links = root.playerLinks ?: root.currentEpisode?.playerLinks ?: return emptyList()
        return links.filter { !it.decodedUrl.isNullOrBlank() }.mapNotNull { p ->
            val lang = when (p.language?.lowercase()) {
                "french", "fr" -> "VF"
                null, "" -> null
                else -> p.language?.uppercase()?.take(8)
            }
            HostLink(p.decodedUrl!!, lang, p.quality?.take(40)?.takeIf { it.isNotBlank() } ?: "Movix", "movix")
        }
    }

    /** api.movix.cash/api/fstream/… — liens french-stream avec vraies étiquettes VFQ/VF/VOSTFR (films). */
    private suspend fun movixFstreamLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        if (season != null) return emptyList() // films uniquement
        val json = runCatching {
            app.get("https://api.movix.cash/api/fstream/movie/$tmdb", headers = baseHeaders).text
        }.getOrNull() ?: return emptyList()
        val root = AppUtils.parseJson<MovixFstreamResponse>(json)
        val out = mutableListOf<HostLink>()
        root.players?.forEach { (lang, entries) ->
            entries.forEach { e ->
                if (!e.url.isNullOrBlank()) {
                    val cleanLang = when (lang) {
                        "VFQ", "VFF" -> "VF"
                        "Default" -> null
                        else -> lang
                    }
                    out += HostLink(e.url!!, cleanLang, e.player ?: "FStream", "fstream")
                }
            }
        }
        return out
    }

    /** primesrc.me/api/v1/… — ~34 serveurs via clés ; /l est protégé Cloudflare (best effort). */
    private suspend fun primesrcLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val type = if (season != null && episode != null) "tv" else "movie"
        val sUrl = buildString {
            append("https://primesrc.me/api/v1/s?type=").append(type).append("&tmdb=").append(tmdb)
            if (season != null && episode != null) append("&season=").append(season).append("&episode=").append(episode)
        }
        val json = app.get(sUrl, headers = baseHeaders).text
        val root = AppUtils.parseJson<PrimeSrcServers>(json)
        val servers = root.servers ?: return emptyList()
        // Dédupliqué par nom de serveur, max 14 pour rester raisonnable
        val picked = servers.distinctBy { it.name }.take(14)

        val out = mutableListOf<HostLink>()
        val sem = Semaphore(4)
        coroutineScope {
            picked.map { srv ->
                async(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        val link = runCatching {
                            app.get(
                                "https://primesrc.me/api/v1/l?key=${srv.key}",
                                headers = baseHeaders,
                                interceptor = cfKiller
                            ).text
                        }.getOrNull() ?: return@async
                        val parsed = AppUtils.tryParseJson<PrimeSrcLink>(link) ?: return@async
                        val target = parsed.link?.takeIf { it.startsWith("http") } ?: return@async
                        val lang = when (srv.audioLanguage?.lowercase()) {
                            "fr", "french" -> "VF"
                            null, "", "en" -> null
                            else -> srv.audioLanguage?.uppercase()?.take(8)
                        }
                        synchronized(out) { out += HostLink(target, lang, srv.name ?: "PrimeSrc", "primesrc") }
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll()
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Extraction générique de secours (avec filtre anti-liens invalides)
    // -------------------------------------------------------------------------
    private suspend fun genericExtract(
        embedUrl: String,
        label: String,
        lang: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(
            embedUrl,
            referer = mainUrl,
            headers = baseHeaders
        ).text

        val links = LinkedHashSet<String>()
        Regex("""(?:og:video(?::secure_url)?|contentUrl|embedUrl)"?\s*(?:content|=|:)\s*["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""(?:file|src|url|source)\s*[=:]\s*["'](https?://[^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        directStreamRegex.findAll(page).map { it.value }.forEach { links.add(it) }

        links.asSequence()
            .filter { it.startsWith("http") }
            .map { it.replace("&amp;", "&") }
            .filter { link -> junkFilterRegex.containsMatchIn(link).not() }
            .filter { it.endsWith(".m3u8") || it.endsWith(".mp4") || it.endsWith(".webm") || directStreamRegex.matches(it) }
            .distinct()
            .forEach { link ->
                val name = "Zenix · $label" + (lang?.let { " · $it" } ?: "")
                callback(
                    newExtractorLink(name, name, link) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                        this.type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }

        links.isNotEmpty()
    }.getOrDefault(false)

    // -------------------------------------------------------------------------
    // Carte du catalogue
    // -------------------------------------------------------------------------
    private fun parseCard(el: Element): SearchResponse? {
        val href = el.attr("href").trim()
        if (href.isBlank() || href.startsWith("javascript")) return null
        val url = fixUrl(href)
        val isTv = "/tv-show/" in url

        var title = el.attr("aria-label").trim()
        if (title.isBlank()) {
            title = el.selectFirst(".zenix-card__title-wrapper img")?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        // data-src (lazyload) avant src (pixel gif de placeholder)
        val poster = el.selectFirst(".zenix-card__img")?.let { img ->
            listOf("data-src", "data-original", "src").firstNotNullOfOrNull { attr ->
                img.attr(attr).takeIf { it.startsWith("http") }
            }
        }
        val score = el.selectFirst(".zenix-card__badge--rating")
            ?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull()
        val year = el.selectFirst(".zenix-card__meta > span")
            ?.text()?.trim()?.toIntOrNull()

        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = score?.let { Score.from10(it) }
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.score = score?.let { Score.from10(it) }
                this.year = year
            }
        }
    }

    private fun jsUnescape(s: String): String = s
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("&amp;", "&")

    private val selectStreamRegex =
        Regex("""selectStream\(\d+,\s*'(.*?)',\s*'(.*?)',\s*'([a-zA-Z_]+)'\)""")

    private val episodeRegex = Regex("""/episode/[^/]+/(\d+)-(\d+)""")

    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")

    /** Filtre les faux positifs (images, pubs, sous-titres, YouTube…) qui causent
     *  des erreurs de lecture du type « container unsupported ». */
    private val junkFilterRegex = Regex(
        """(?i)(youtube|youtu\.be|dailymotion|\.jpg|\.jpeg|\.png|\.gif|\.webp|\.svg|\.vtt|\.srt|""" +
            """/ads?/|adserve|adservice|adsystem|doubleclick|banner|/pixel|analytics|/thumb|poster|trailer)"""
    )
}

// -------------------------------------------------------------------------
// Modèles JSON
// -------------------------------------------------------------------------

// /ajax/search/suggest
@JsonIgnoreProperties(ignoreUnknown = true)
data class ZenixSuggestResponse(
    @JsonProperty("posts") val posts: List<ZenixSuggestPost> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ZenixSuggestPost(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("quality") val quality: String? = null
)

// apis.wavewatch.top/apiwiflix.php (allSources)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiWiflixSource(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("language") val language: String? = null
)

// api.movix.cash/api/tmdb/…
@JsonIgnoreProperties(ignoreUnknown = true)
data class MovixResponse(
    @JsonProperty("player_links") val playerLinks: List<MovixPlayerLink>? = null,
    @JsonProperty("current_episode") val currentEpisode: MovixCurrentEpisode? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovixCurrentEpisode(
    @JsonProperty("player_links") val playerLinks: List<MovixPlayerLink>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovixPlayerLink(
    @JsonProperty("decoded_url") val decodedUrl: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("language") val language: String? = null
)

// api.movix.cash/api/fstream/…
@JsonIgnoreProperties(ignoreUnknown = true)
data class MovixFstreamResponse(
    @JsonProperty("players") val players: Map<String, List<MovixFstreamPlayer>>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovixFstreamPlayer(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("player") val player: String? = null,
    @JsonProperty("quality") val quality: String? = null
)

// primesrc.me/api/v1/…
@JsonIgnoreProperties(ignoreUnknown = true)
data class PrimeSrcServers(
    @JsonProperty("servers") val servers: List<PrimeSrcServer>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrimeSrcServer(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("key") val key: String = "",
    @JsonProperty("audio_language") val audioLanguage: String? = null,
    @JsonProperty("audio_type") val audioType: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrimeSrcLink(
    @JsonProperty("link") val link: String? = null
)
