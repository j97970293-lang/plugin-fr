package com.xalaflix

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ===========================================================================
// Xalaflix — films & séries VF/VOSTFR multi-serveurs (Laravel + Livewire)
// ===========================================================================
// Site : https://xalaflix.tax — la page d'annonce https://xalaflix.online
// publie le domaine courant (résolution automatique au démarrage, avec
// validation ; ⚙ pour forcer une adresse).
//   Listes    : /trending · /movies?page=N · /tv-shows?page=N · /top-imdb
//   Recherche : /search/{q} (espaces = %20)
//   Fiches    : /movie/{slug} · /tv-show/{slug} — saisons via Livewire
//               (updateSeason), épisodes /episode/{slug}/{s}-{e}
//   Serveurs  : const videos = [{server_name, version, link}] (vidzy XOR,
//               kakaflix dood/voe, vidsrc.xyz, flixeo…) + fallback iframes —
//               en parallèle, bornés à 15 s/hôte.
//   Supplément : id TMDB (présent dans les liens du site) → lecteurs publics
//               (Videasy, VidFast, VidSrc.cc, 2Embed…) + vidsrc.buzz.
// ===========================================================================
@CloudstreamPlugin
class XalaflixPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        XalaflixProvider.appContext = context.applicationContext
        registerMainAPI(XalaflixProvider())
        openSettings = { ctx -> XalaflixProvider.showSettings(ctx) }
    }
}

class XalaflixProvider : MainAPI() {
    companion object {
        const val DEFAULT_URL = "https://xalaflix.tax"
        // registry public : page d'annonce du domaine courant
        const val REGISTRY_URL = "https://xalaflix.online/"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "movix_settings"
        private const val PREF_URL = "site_url"

        fun customUrl(): String? = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                ?.getString(PREF_URL, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull()

        fun currentUrl(): String = customUrl() ?: DEFAULT_URL

        fun setSiteUrl(context: android.content.Context, url: String?) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .apply()
        }

        fun showSettings(context: android.content.Context) {
            val input = android.widget.EditText(context).apply {
                setText(currentUrl()); hint = "https://xalaflix.tax"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de Xalaflix")
                .setMessage("Laissez vide pour la résolution automatique via la page d'annonce officielle (xalaflix.online).")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Auto") { _, _ -> setSiteUrl(context, null) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "Xalaflix"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun headers() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    private val cfKiller by lazy { com.lagradost.cloudstream3.network.CloudflareKiller() }

    @Volatile
    private var resolvedFromRegistry: String? = null

    /**
     * Résolution du domaine courant : adresse ⚙ → page d'annonce officielle
     * (xalaflix.online, liens « xalaflix.* ») → défaut. Le résultat est
     * validé sur la page d'accueil (cartes /movie|tv-show/).
     */
    private suspend fun ensureDomain(): String {
        customUrl()?.let { mainUrl = it; return it }
        resolvedFromRegistry?.let { mainUrl = it; return it }
        val candidates = mutableListOf<String>()
        runCatching {
            val page = app.get(REGISTRY_URL, headers = headers()).text
            Regex("""https?://xalaflix\.[a-z]{2,6}/?""").findAll(page)
                .map { it.value.trimEnd('/') }.toCollection(candidates)
        }
        candidates += DEFAULT_URL
        for (c in candidates.distinct()) {
            val ok = runCatching {
                val home = app.get(c, headers = headers()).text
                Regex("""/(?:movie|tv-show)/[a-z0-9-]+""").containsMatchIn(home)
            }.getOrDefault(false)
            if (ok) {
                resolvedFromRegistry = c
                mainUrl = c
                return c
            }
        }
        mainUrl = DEFAULT_URL
        return DEFAULT_URL
    }

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    override val mainPage = mainPageOf(
        "trending" to "🔥 Tendances",
        "movies" to "🎬 Films",
        "tv-shows" to "📺 Séries",
        "top-imdb" to "⭐ Top IMDb"
    )

    // -------------------------------------------------------------------------
    // Listes — <a href=/movie|tv-show/{slug}> + <img data-src=… alt=TITRE>
    // -------------------------------------------------------------------------
    private fun parseCards(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex("""<a href="(?:https?://[^"]*?)/(movie|tv-show)/([a-z0-9-]+)"[\s\S]{0,400}?<img[^>]+data-src="([^"]+)"[^>]*alt="([^"]*)"""")
            .findAll(html).forEach { m ->
                val (kind, slug, img, title) = m.destructured
                if (title.isBlank()) return@forEach
                out[slug] = if (kind == "tv-show") {
                    newTvSeriesSearchResponse(title, "$mainUrl/$kind/$slug") { this.posterUrl = img }
                } else {
                    newMovieSearchResponse(title, "$mainUrl/$kind/$slug", TvType.Movie) { this.posterUrl = img }
                }
            }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureDomain()
        val base = when (request.data) {
            "trending", "top-imdb" -> "$mainUrl/${request.data}"
            else -> "$mainUrl/${request.data}?page=$page"
        }
        val html = runCatching { app.get(base, headers = headers(), interceptor = cfKiller).text }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        // trending/top-imdb : une seule page ; listes : ?page=N
        val hasNext = request.data !in setOf("trending", "top-imdb") && items.size >= 20
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    // -------------------------------------------------------------------------
    // Recherche — GET /search/{q} (page dédiée, mêmes cartes que les listes)
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        ensureDomain()
        // ⚠ URLEncoder produit « + » pour les espaces → 0 résultat sur movix
        // (le site attend %20, encodage de chemin et non de formulaire)
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isEmpty()) return emptyList()
        val html = runCatching {
            app.get("$mainUrl/search/$q", headers = headers(), interceptor = cfKiller).text
        }.getOrNull() ?: return emptyList()
        return parseCards(html)
    }

    /** Extrait (et déséchappe) le snapshot JSON d'un composant Livewire. */
    private fun extractSnapshot(html: String, componentName: String): String? {
        Regex("""wire:snapshot="([^"]*)"""").findAll(html).forEach { m ->
            val snap = m.groupValues[1]
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
            if (""""name":"$componentName"""" in snap) return snap
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Fiches
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        ensureDomain()
        val html = runCatching { app.get(url, headers = headers(), interceptor = cfKiller).text }.getOrNull()
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")
        val slug = url.trimEnd('/').substringAfterLast('/')
        val isSeries = "/tv-show/" in url

        val title = Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1)
            ?.replace(Regex("""\s+(?:Streaming|streaming|Stream|stream).*$"""), "")
            ?.replace(Regex("""\s+(?:Complet|complet|VF/VOSTFR|VF|VOSTFR).*$"""), "")
            ?.trim()?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
        // fragment de secours : titre + type survivent dans le data des
        // épisodes/films → multi-sources servis même si la page échoue
        val frag = "#t=" + java.net.URLEncoder.encode(title, "UTF-8") +
            "#m=" + (if (isSeries) "tv" else "movie")
        val poster = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta name="description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        val year = Regex("""(\d{4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val score = Regex("""([\d.]+)\s*(?:/|sur)\s*10""").find(html)?.groupValues?.get(1)?.toDoubleOrNull()

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url + frag) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score?.let { com.lagradost.cloudstream3.Score.from10(it) }
            }
        }

        // Série : épisodes de la saison par défaut + les autres via Livewire
        val episodes = mutableListOf<Episode>()
        fun parseEpisodeCards(block: String, season: Int) {
            Regex("""<a href="(?:https?://[^"]*?)/episode/([a-z0-9-]+)/(\d+)-(\d+)"""")
                .findAll(block).forEach { m ->
                    val (s, e) = m.groupValues[2].toInt() to m.groupValues[3].toInt()
                    episodes += newEpisode("$mainUrl/episode/${m.groupValues[1]}/$s-$e" + frag) {
                        this.name = "Épisode $e"
                        this.season = s
                        this.episode = e
                        this.posterUrl = poster
                    }
                }
        }
        // épisodes rendus (saison par défaut)
        parseEpisodeCards(html, 1)
        // boutons des autres saisons : wire:click="updateSeason('id')"
        val seasonIds = Regex("""updateSeason\('(\d+)'\)""").findAll(html).map { it.groupValues[1] }.distinct().toList()
        val renderedEpCount = episodes.size
        if (seasonIds.size > 1 || (seasonIds.isNotEmpty() && renderedEpCount == 0)) {
            val snapshot = extractSnapshot(html, "season-component")
            if (snapshot != null) {
                seasonIds.forEach { sid ->
                    // évite de recharger la saison déjà rendue : la réponse
                    // inclut de toute façon la saison demandée
                    val body = mapOf(
                        "components" to listOf(
                            mapOf(
                                "snapshot" to snapshot,
                                "updates" to emptyMap<String, String>(),
                                "calls" to listOf(mapOf("method" to "updateSeason", "params" to listOf(sid)))
                            )
                        )
                    )
                    val resp = runCatching {
                        app.post(
                            "$mainUrl/livewire/update",
                            headers = headers() + mapOf("X-Livewire" to "true"),
                            json = body
                        ).text
                    }.getOrNull() ?: return@forEach
                    val block = runCatching {
                        AppUtils.parseJson<LivewireResponse>(resp).components.firstOrNull()?.effects?.html
                    }.getOrNull() ?: return@forEach
                    parseEpisodeCards(block, 1)
                }
            }
        }
        val unique = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
        if (unique.isEmpty()) {
            throw com.lagradost.cloudstream3.ErrorLoadingException("Aucun épisode trouvé")
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, unique) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score?.let { com.lagradost.cloudstream3.Score.from10(it) }
        }
    }

    // -------------------------------------------------------------------------
    // Lecteurs — versionController() : videos = [{server_name,label,version,link}]
    // -------------------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MvVideo(
        val server_name: String? = null,
        val label: String? = null,
        val version: String? = null,
        val link: String? = null,
        val type: String? = null
    )

    // ── DTOs multi-sources réseau (api.movix.men) ──
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsPurstream(val sources: List<XsPurstreamSource> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsPurstreamSource(val url: String? = null, val name: String? = null, val format: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsPlayer(val url: String? = null, val name: String? = null, val player: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsWiflix(
        val players: Map<String, List<XsPlayer>> = emptyMap(),
        val movie: Map<String, List<XsPlayer>> = emptyMap(),
        val episodes: Map<String, Map<String, List<XsPlayer>>> = emptyMap()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsFstreamTv(val episodes: Map<String, XsFstreamEpisode> = emptyMap())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsFstreamEpisode(val languages: Map<String, List<XsPlayer>> = emptyMap())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsFstreamMovie(val links: Map<String, List<XsPlayer>> = emptyMap())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsCpasmal(val links: Map<String, List<XsPlayer>> = emptyMap())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsImdbRes(val series: List<XsImdbSeries> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsImdbSeries(val seasons: List<XsImdbSeason> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsImdbSeason(val episodes: List<XsImdbEpisode> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsImdbEpisode(val number: String? = null, val versions: Map<String, XsImdbVersion> = emptyMap())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsImdbVersion(val players: List<XsImdbPlayer> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsImdbPlayer(val link: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsLinksData(val id: String? = null, val links: List<Any?> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsLinksMovie(val data: XsLinksData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsLinksTv(val data: List<XsLinksData> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsDownload(val sources: List<XsDownloadSource>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsDownloadSource(
        val m3u8: String? = null,
        val src: String? = null,
        val quality: String? = null,
        val language: String? = null
    )

    // --- langues autorisées / refusées (VF/VOSTFR only) ---
    private fun wantedLangKey(k: String): Boolean {
        val l = k.trim().lowercase()
        if (l.isEmpty()) return true
        return l in setOf("vf", "vostfr", "truefrench", "french", "fr", "multi", "vo", "original", "en", "english") ||
            "french" in l || l.startsWith("vf") || l.startsWith("vostfr")
    }

    private fun unwantedLangName(n: String): Boolean {
        val l = n.lowercase()
        return listOf(
            "spanish", "español", "espanol", "latino", "castellano", "deutsch", "german",
            "italiano", "italian", "hindi", "tamil", "telugu", "russian", "chinese",
            "korean", "japanese", "portugues", "português", "turkish", "arabic", "dublado"
        ).any { it in l }
    }

    // --- id TMDB par recherche de titre (quand la page ne répond pas) ---
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsSearch(val results: List<XsSearchResult> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XsSearchResult(
        val id: Int? = null,
        val media_type: String? = null,
        val title: String? = null,
        val name: String? = null
    )

    private val tmdbIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun tmdbFromTitle(title: String, isTv: Boolean): String? {
        val key = (if (isTv) "tv|" else "movie|") + title.lowercase().trim()
        tmdbIdCache[key]?.let { return it }
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        val j = runCatching {
            app.get(
                "https://api.themoviedb.org/3/search/multi?api_key=f3d757824f08ea2cff45eb8f47ca3a1e" +
                    "&language=fr-FR&query=$enc&page=1&include_adult=false",
                headers = headers()
            ).text
        }.getOrNull() ?: return null
        val want = if (isTv) "tv" else "movie"
        val res = runCatching { AppUtils.parseJson<XsSearch>(j) }.getOrNull()?.results ?: return null
        val hit = res.firstOrNull { it.media_type == want && !it.title.isNullOrBlank() }
            ?: res.firstOrNull { it.media_type == want }
        return hit?.id?.toString()?.also { tmdbIdCache[key] = it }
    }

    private suspend fun networkMultiSources(
        tmdb: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val api = "https://api.movix.men/api"
        val site = "movix.men"
        val apiH = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to "https://$site",
            "Referer" to "https://$site/"
        )
        val found = java.util.concurrent.atomic.AtomicBoolean(false)
        suspend fun emit(label: String, url: String, quality: Int? = null) {
            if (!url.startsWith("http")) return
            found.set(true)
            callback(
                newExtractorLink(label, label, url) {
                    this.quality = quality ?: Qualities.Unknown.value
                    this.type = if (".m3u8" in url) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        }
        suspend fun extract(label: String, url: String) {
            if (unwantedLangName(label)) return
            if (directStreamRegex.matches(url)) {
                emit(label, url)
                return
            }
            runCatching {
                withTimeoutOrNull(15_000) {
                    val ok = loadExtractor(url, "https://$site/", subtitleCallback) { l ->
                        found.set(true)
                        callback(l)
                    }
                    if (!ok && genericExtract(url, label, subtitleCallback) { e ->
                            found.set(true)
                            callback(e)
                        }) found.set(true)
                }
            }
        }
        coroutineScope {
            // 1) Purstream : HLS direct (« pulse | 1080p | MULTI »)
            launch(Dispatchers.IO) {
                runCatching {
                    val u = if (isTv) "$api/purstream/tv/$tmdb/stream?season=${season ?: 1}&episode=${episode ?: 1}"
                        else "$api/purstream/movie/$tmdb/stream"
                    val j = app.get(u, headers = apiH).text
                    AppUtils.parseJson<XsPurstream>(j).sources.forEach { src ->
                        val url = src.url?.takeIf { it.startsWith("http") } ?: return@forEach
                        if (unwantedLangName(src.name ?: "")) return@forEach
                        val q = src.name?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
                        emit("Purstream · ${src.name ?: "stream"}", url, q)
                    }
                }
            }
            // 2) Wiflix (via cinestream) : players VF/VOSTFR
            launch(Dispatchers.IO) {
                runCatching {
                    val u = if (isTv) "$api/wiflix/tv/$tmdb/${season ?: 1}" else "$api/wiflix/movie/$tmdb"
                    val j = app.get(u, headers = apiH).text
                    val res = AppUtils.parseJson<XsWiflix>(j)
                    val langs: Map<String, List<XsPlayer>> = if (isTv) {
                        episode?.toString()?.let { res.episodes[it] }
                            ?: res.episodes.entries.firstOrNull()?.value
                        ?: emptyMap()
                    } else {
                        res.players.ifEmpty { res.movie }
                    }
                    langs.filterKeys { wantedLangKey(it) }.forEach { (lang, list) ->
                        list.forEach { p ->
                            val url = p.url?.takeIf { it.startsWith("http") } ?: return@forEach
                            extract("Wiflix · ${p.name ?: p.player ?: ""} · ${lang.uppercase()}", url)
                        }
                    }
                }
            }
            // 3) FrenchStream : par langue (film) / par épisode (série)
            launch(Dispatchers.IO) {
                runCatching {
                    val pairs: List<Pair<String, XsPlayer>> = if (isTv) {
                        val j = app.get("$api/fstream/tv/$tmdb/season/${season ?: 1}", headers = apiH).text
                        val eps = AppUtils.parseJson<XsFstreamTv>(j).episodes
                        val ep = episode?.toString()?.let { eps[it] } ?: eps.entries.firstOrNull()?.value
                        ep?.languages?.filterKeys { wantedLangKey(it) }?.flatMap { (lang, list) ->
                            list.map { lang to it }
                        } ?: emptyList()
                    } else {
                        val j = app.get("$api/fstream/movie/$tmdb", headers = apiH).text
                        AppUtils.parseJson<XsFstreamMovie>(j).links.filterKeys { wantedLangKey(it) }
                            .flatMap { (lang, list) -> list.map { lang to it } }
                    }
                    pairs.forEach { (lang, p) ->
                        val url = p.url?.takeIf { it.startsWith("http") } ?: return@forEach
                        extract("FrenchStream · ${p.player ?: p.name ?: ""} · ${lang.uppercase()}", url)
                    }
                }
            }
            // 4) Cpasmal (souvent 404 → silencieux)
            launch(Dispatchers.IO) {
                runCatching {
                    val u = if (isTv) "$api/cpasmal/tv/$tmdb/${season ?: 1}/${episode ?: 1}"
                        else "$api/cpasmal/movie/$tmdb"
                        val j = app.get(u, headers = apiH).text.replace("\"players\":", "\"links\":")
                    AppUtils.parseJson<XsCpasmal>(j).links.filterKeys { wantedLangKey(it) }
                        .flatMap { (lang, list) -> list.map { lang to it } }
                        .forEach { (lang, p) ->
                            val url = p.url?.takeIf { it.startsWith("http") } ?: return@forEach
                            extract("Cpasmal · ${lang.uppercase()}", url)
                        }
                }
            }
            // 5) IMDB (surtout séries)
            launch(Dispatchers.IO) {
                runCatching {
                    val j = app.get("$api/imdb/${if (isTv) "tv" else "movie"}/$tmdb", headers = apiH).text
                    AppUtils.parseJson<XsImdbRes>(j).series.asSequence()
                        .flatMap { it.seasons.asSequence() }
                        .flatMap { it.episodes.asSequence() }
                        .filter { episode == null || it.number == episode.toString() }
                        .flatMap { ep ->
                            ep.versions.filterKeys { wantedLangKey(it) }.flatMap { (lang, ver) ->
                                ver.players.map { lang to it }
                            }
                        }
                        .forEach { (lang, p) ->
                            val url = p.link?.takeIf { it.startsWith("http") } ?: return@forEach
                            extract("IMDb · ${lang.uppercase()}", url)
                        }
                }
            }
            // 6) liens directs (mp4/m3u8 + hébergeurs)
            launch(Dispatchers.IO) {
                runCatching {
                    val q = if (isTv) "?season=${season ?: 1}&episode=${episode ?: 1}" else ""
                    val j = app.get("$api/links/${if (isTv) "tv" else "movie"}/$tmdb$q", headers = apiH).text
                    val list: List<Any?> = if (isTv) {
                        AppUtils.parseJson<XsLinksTv>(j).data.flatMap { it.links }
                    } else {
                        AppUtils.parseJson<XsLinksMovie>(j).data?.links ?: emptyList()
                    }
                    list.forEach { el ->
                        val url = (el as? String)?.takeIf { it.startsWith("http") }
                            ?: (el as? Map<*, *>)?.get("url") as? String
                        if (url != null) {
                            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "lien"
                            extract("Réseau · $host", url)
                        }
                    }
                }
            }
            // 7) téléchargements (m3u8 directs avec langue + qualité)
            launch(Dispatchers.IO) {
                runCatching {
                    val t = title?.takeIf { it.isNotBlank() } ?: run {
                        val j = app.get("$api/tmdb/${if (isTv) "tv" else "movie"}/$tmdb", headers = apiH).text
                        Regex("""["']?(?:title|name)["']?\s*:\s*["']([^"']+)["']""").find(j)?.groupValues?.get(1)
                    } ?: return@runCatching
                    val enc = java.net.URLEncoder.encode(t, "UTF-8")
                    val sj = app.get("$api/search?title=$enc", headers = apiH).text
                    val dlId = Regex("""["']id["']\s*:\s*(\d+)""").find(sj)?.groupValues?.get(1) ?: return@runCatching
                    val du = if (isTv) "$api/series/download/$dlId/season/${season ?: 1}/episode/${episode ?: 1}"
                        else "$api/films/download/$dlId"
                    val dj = app.get(du, headers = apiH).text
                    AppUtils.parseJson<XsDownload>(dj).sources?.forEach { ds ->
                        val url = (ds.m3u8 ?: ds.src)?.takeIf { it.startsWith("http") } ?: return@forEach
                        val lang = ds.language ?: ""
                        if (unwantedLangName(lang) && !wantedLangKey(lang)) return@forEach
                        val q = ds.quality?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
                        emit("Réseau · ${lang.ifEmpty { "direct" }}${if (q != null) " · ${q}p" else ""}", url, q)
                    }
                }
            }
        }
        return found.get()
    }

    // résout /api/stream?… → URL réelle de l'hébergeur :
    // 302 Location, sinon (page 200) première URL externe du corps

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureDomain()
        // fragment encodé au load() : #t=titre#m=movie|tv — les multi-sources
        // restent servis même si la page (Cloudflare/timeout) ne répond pas
        val fragTitleEnc = data.substringAfter("#t=", "").substringBefore("#")
        val fragType = data.substringAfter("#m=", "")
        val pageUrl = data.substringBefore("#")
        val html = runCatching {
            app.get(pageUrl, headers = headers(), interceptor = cfKiller).text
        }.getOrNull()

        // slug + type + épisode + titre, pour les multi-sources
        val path = pageUrl.substringAfter("://", "").substringAfter('/', "").trim('/')
        val parts = path.split("/")
        val isTvEpisode = parts.firstOrNull() == "episode"
        val isTvShow = fragType == "tv" || parts.firstOrNull() == "tv-show" || isTvEpisode
        val slug = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val se = parts.getOrNull(2)?.split("-")
        val season = se?.getOrNull(0)?.toIntOrNull()
        val episode = se?.getOrNull(1)?.toIntOrNull()
        val titleForSearch = fragTitleEnc.takeIf { it.isNotBlank() }?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
        } ?: Regex("""<title>([^<]*)</title>""").find(html ?: "")
            ?.groupValues?.get(1)?.trim()
        // id TMDB : présent dans les liens des lecteurs du site, sinon par titre
        val tmdb = Regex("""[?&]tmdb=(\d+)""").find(html ?: "")?.groupValues?.get(1)
            ?: titleForSearch?.takeIf { it.isNotBlank() }?.let { tmdbFromTitle(it, isTvShow) }

        // supplément : réseau multi-sources + lecteurs publics TMDB + vidsrc.buzz
        suspend fun tryAggregator(): Boolean {
            val t = tmdb ?: return false
            var found = false
            coroutineScope {
                launch(Dispatchers.IO) {
                    runCatching {
                        withTimeoutOrNull(25_000) {
                            if (networkMultiSources(t, isTvShow, season, episode, titleForSearch, subtitleCallback) { l ->
                                    found = true
                                    callback(l)
                                }) found = true
                        }
                    }
                }
                launch(Dispatchers.IO) {
                    runCatching {
                        withTimeoutOrNull(20_000) {
                            if (frembedNetworkLinks(t, isTvShow, season, episode, subtitleCallback) { l ->
                                    found = true
                                    callback(l)
                                }) found = true
                        }
                    }
                }
                val embeds = buildList {
                    if (isTvShow && season != null && episode != null) {
                        add("https://player.videasy.net/tv/$t/$season/$episode" to "Videasy")
                        add("https://vidfast.pro/tv/$t/$season/$episode?autoPlay=true&sub=fr" to "VidFast")
                        add("https://vidsrc.cc/v2/embed/tv/$t/$season/$episode" to "VidSrc.cc")
                        add("https://www.2embed.cc/embedtv/$t&s=$season&e=$episode" to "2Embed")
                        add("https://111movies.com/tv/$t/$season/$episode" to "111Movies")
                        add("https://vidnest.fun/tv/$t/$season/$episode" to "VidNest")
                    } else {
                        add("https://player.videasy.net/movie/$t" to "Videasy")
                        add("https://vidfast.pro/movie/$t?autoPlay=true&sub=fr" to "VidFast")
                        add("https://vidsrc.cc/v2/embed/movie/$t" to "VidSrc.cc")
                        add("https://www.2embed.cc/embed/$t" to "2Embed")
                        add("https://111movies.com/movie/$t" to "111Movies")
                        add("https://vidnest.fun/movie/$t" to "VidNest")
                    }
                }
                embeds.forEach { (u, label) ->
                    launch(Dispatchers.IO) {
                        runCatching {
                            withTimeoutOrNull(15_000) {
                                val nm = "Xalaflix+ · $label"
                                val ok = loadExtractor(u, mainUrl, subtitleCallback) { l ->
                                    found = true
                                    callback(l)
                                }
                                if (!ok && genericExtract(u, nm, subtitleCallback) { l ->
                                        found = true
                                        callback(l)
                                    }) found = true
                            }
                        }
                    }
                }
            }
            val agg = runCatching { vidsrcBuzzLinks(t, isTvShow, season, episode, callback) }.getOrDefault(false)
            return found || agg
        }

        // page injoignable (CF, timeout mobile) : servir quand même les
        // multi-sources grâce au tmdb récupéré par titre
        if (html == null) return tryAggregator()

        val foundFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val videosJson = Regex("""(?:const|var|let)\s+videos\s*=\s*(\[[\s\S]*?\]);""").find(html)?.groupValues?.get(1)
        val videos = videosJson?.let { runCatching { AppUtils.parseJson<List<MvVideo>>(it) }.getOrNull() }

        // fallback : iframes d'embed visibles dans le HTML (en parallèle, bornées)
        if (videos.isNullOrEmpty()) {
            coroutineScope {
                Regex("""<iframe[^>]*\ssrc="(https?://[^"]+)"""").findAll(html).forEach { m ->
                    val u = m.groupValues[1]
                    if (u.contains("xalaflix.") || u.contains("google") || u.contains("facebook")) return@forEach
                    launch(Dispatchers.IO) {
                        runCatching {
                            withTimeoutOrNull(15_000) {
                                if (loadExtractor(u, pageUrl, subtitleCallback) { l ->
                                        foundFlag.set(true)
                                        callback(l)
                                    }) foundFlag.set(true)
                            }
                        }
                    }
                }
            }
            if (foundFlag.get()) return true
            return tryAggregator()
        }

        // serveurs du site — EN PARALLÈLE, bornés 15 s, sans langues non-FR
        coroutineScope {
            videos.forEach { v ->
                launch(Dispatchers.IO) {
                    val link = v.link?.takeIf { it.startsWith("http") } ?: return@launch
                    val version = v.version?.takeIf { it.isNotBlank() } ?: ""
                    val server = v.server_name?.takeIf { it.isNotBlank() } ?: name
                    if (unwantedLangName(server) || unwantedLangName(version)) return@launch
                    val label = buildString {
                        append(server)
                        if (version.isNotBlank()) append(" · $version")
                        if (!v.label.isNullOrBlank()) append(" · ${v.label}")
                    }
                    runCatching {
                        withTimeoutOrNull(15_000) {
                            val ok = loadExtractor(link, mainUrl, subtitleCallback) { l ->
                                foundFlag.set(true)
                                callback(l)
                            }
                            if (!ok && genericExtract(link, label, subtitleCallback, callback)) foundFlag.set(true)
                        }
                    }
                }
            }
        }

        // ---- Supplément agrégateur (multi-sources + vidsrc.buzz) ----
        val aggFound = tryAggregator()
        return foundFlag.get() || aggFound
    }

    // -------------------------------------------------------------------------
    // vidsrc.buzz — agrégateur TMDB, HLS proxysés directs
    // -------------------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsQ(val type: String? = null, val id: String? = null, val s: Int? = null, val e: Int? = null, val t: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsServer(val ref: String? = null, val name: String? = null)

    // ⚠ l'API renvoie un OBJET {"status":..,"servers":[..]}, pas une liste brute
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsSources(val status: String? = null, val servers: List<VsServer> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XfImdb(val imdb_id: String? = null)

    // liens de l'API du réseau Frembed (url RELATIVE /api/stream?…)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XfLinks(val links: List<XfLink> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XfLink(
        val url: String? = null,
        val lang: String? = null,
        val label: String? = null,
        val host: XfHost? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class XfHost(val name: String? = null, val slug: String? = null)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsPlay(val url: String? = null)

    // --- headers exacts observés quand un navigateur ouvre /api/stream en iframe
    private val streamNavHeaders = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Safari/537.36"
            ),
        "Accept" to (
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,image/apng,*/*;q=0.8," +
                "application/signed-exchange;v=b3;q=0.7"
            ),
        "Accept-Language" to "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    private suspend fun vidsrcBuzzLinks(
        tmdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val foundFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        fun buildEmbed(id: String): String = when {
            isTv && season != null && episode != null ->
                "https://vidsrc.buzz/embed/tv/$id/$season/$episode"
            isTv -> "https://vidsrc.buzz/embed/tv/$id/1/1"
            else -> "https://vidsrc.buzz/embed/movie/$id"
        }
        tryBuzzEmbed(buildEmbed(tmdbId), callback, foundFlag)
        if (!foundFlag.get()) {
            // certains contenus n'ont de serveurs QUE par leur id IMDb
            val imdb = fetchImdbId(tmdbId, isTv)
            if (imdb != null && imdb != tmdbId) tryBuzzEmbed(buildEmbed(imdb), callback, foundFlag)
        }
        return foundFlag.get()
    }

    private suspend fun fetchImdbId(tmdbId: String, isTv: Boolean): String? {
        val path = if (isTv) "tv" else "movie"
        return runCatching {
            val j = app.get(
                "https://api.themoviedb.org/3/$path/$tmdbId/external_ids?api_key=f3d757824f08ea2cff45eb8f47ca3a1e&language=fr-FR",
                headers = headers()
            ).text
            AppUtils.parseJson<XfImdb>(j).imdb_id?.takeIf { it.startsWith("""tt""") }
        }.getOrNull()
    }

    private suspend fun tryBuzzEmbed(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
        foundFlag: java.util.concurrent.atomic.AtomicBoolean
    ): Boolean {
        val vsHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Referer" to embedUrl
        )
        val html = runCatching { app.get(embedUrl, headers = vsHeaders).text }.getOrNull() ?: return false
        val qm = Regex("""var Q = (\\{.*?\\});""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return false
        val q = runCatching { AppUtils.parseJson<VsQ>(qm.groupValues[1]) }.getOrNull() ?: return false
        val id = q.id?.takeIf { it.isNotBlank() } ?: return false
        val token = q.t?.takeIf { it.isNotBlank() } ?: return false
        val qs = "type=${q.type ?: "movie"}&id=${java.net.URLEncoder.encode(id, "UTF-8")}" +
            "&s=${q.s ?: 0}&e=${q.e ?: 0}&t=${java.net.URLEncoder.encode(token, "UTF-8")}"
        val srcJson = runCatching {
            app.get("https://vidsrc.buzz/pl/api.php?a=sources&$qs", headers = vsHeaders).text
        }.getOrNull() ?: return false
        val servers = runCatching { AppUtils.parseJson<VsSources>(srcJson).servers }.getOrNull() ?: return false
        coroutineScope {
            servers.take(6).forEach { sv ->
                launch(Dispatchers.IO) {
                    val ref = sv.ref?.takeIf { it.isNotBlank() } ?: return@launch
                    if (unwantedLangName(sv.name ?: "")) return@launch
                    val svName = sv.name?.replace(Regex("""^Server\s+"""), "")?.trim()
                        ?.takeIf { it.isNotBlank() } ?: "Agrégateur"
                    var u: String? = null
                    for (attempt in 1..3) {
                        val play = runCatching {
                            app.get(
                                "https://vidsrc.buzz/pl/api.php?a=play&ref=${java.net.URLEncoder.encode(ref, "UTF-8")}" +
                                    "&t=${java.net.URLEncoder.encode(token, "UTF-8")}",
                                headers = vsHeaders
                            ).text
                        }.getOrNull() ?: break
                        u = runCatching { AppUtils.parseJson<VsPlay>(play).url }.getOrNull()
                        if (!u.isNullOrBlank()) break
                        if (attempt < 3) kotlinx.coroutines.delay(1300)
                    }
                    val raw = u?.takeIf { it.isNotBlank() } ?: return@launch
                    val link = if (raw.startsWith("/")) "https://vidsrc.buzz$raw" else raw
                    if (!link.startsWith("http")) return@launch
                    foundFlag.set(true)
                    callback(
                        newExtractorLink("VidSrc $svName", "VidSrc $svName", link) {
                            this.referer = "https://vidsrc.buzz/"
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.M3U8
                        }
                    )
                }
            }
        }
        return foundFlag.get()
    }

    // --- réseau Frembed : serveurs réels du réseau (Voe/Dood/Uqload) par API.
    //     links[].url est RELATIF (/api/stream?…) : cookies + Referer fiche +
    //     headers iframe → 302 vers l'hôte réel, décodé ensuite par loadExtractor.
    private suspend fun frembedNetworkLinks(
        tmdb: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = "https://frembed.surf"
        val contentPage = if (isTv) "$origin/series?id=$tmdb" else "$origin/films?id=$tmdb"
        val apiUrl = when {
            isTv && season != null && episode != null ->
                "$origin/api/series?id=$tmdb&sa=$season&epi=$episode&idType=tmdb"
            isTv -> "$origin/api/series?id=$tmdb&sa=1&epi=1&idType=tmdb"
            else -> "$origin/api/films?id=$tmdb&idType=tmdb"
        }
        runCatching { app.get(contentPage, headers = headers()) }
        val apiJson = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$origin/",
                    "Accept" to "application/json"
                )
            ).text
        }.getOrNull() ?: return false
        val links = runCatching { AppUtils.parseJson<XfLinks>(apiJson).links }.getOrNull() ?: return false
        var found = false
        links.forEach { l ->
            val raw = l.url?.takeIf { it.isNotBlank() } ?: return@forEach
            val streamUrl = if (raw.startsWith("http")) raw else "$origin$raw"
            val hostName = l.host?.name?.takeIf { it.isNotBlank() }
                ?: l.label?.takeIf { it.isNotBlank() } ?: "Serveur"
            val lang = l.lang?.uppercase()?.take(5)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            val label = "Xalaflix+ · Frembed $hostName$lang"
            val target = runCatching {
                val resp = app.get(
                    streamUrl,
                    headers = streamNavHeaders + mapOf("Referer" to contentPage),
                    allowRedirects = false
                )
                when (resp.okhttpResponse.code) {
                    in 300..399 -> resp.okhttpResponse.headers["Location"]?.takeIf { it.startsWith("http") }
                    200 -> Regex("""https?://[a-zA-Z0-9.-]+/[^'	
 <>]+""")
                        .findAll(resp.text)
                        .mapNotNull { it.value }
                        .firstOrNull {
                            "frembed" !in it && "cloudflare" !in it && "static." !in it && "fonts" !in it
                        }
                    else -> null
                }
            }.getOrNull()
            if (target != null) {
                runCatching {
                    withTimeoutOrNull(15_000) {
                        val ok = loadExtractor(target, "$origin/", subtitleCallback) { e ->
                            found = true
                            callback(e)
                        }
                        if (!ok && genericExtract(target, label, subtitleCallback) { e ->
                                found = true
                                callback(e)
                            }) found = true
                    }
                }
            }
        }
        return found
    }

    // -------------------------------------------------------------------------
    // Extraction générique (vidzy XOR, kakaflix/dood, voe, JW p.a.c.k.e.r…)
    // -------------------------------------------------------------------------
    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")
    private val junkFilterRegex = Regex(
        """(?i)(youtube|youtu\.be|dailymotion|\.jpg|\.jpeg|\.png|\.gif|\.webp|\.svg|\.vtt|\.srt|""" +
            """/ads?/|adserve|adservice|adsystem|doubleclick|banner|/pixel|analytics|/thumb|poster|trailer)"""
    )

    private suspend fun genericExtract(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(embedUrl, referer = mainUrl, headers = mapOf("user-agent" to USER_AGENT), interceptor = cfKiller).text
        // page d'attente anti-bot vidzy.org (compte à rebours 180 s) : aucun flux
        if ("Chargement vidéo" in page && "location.reload" in page) return@runCatching false
        val links = LinkedHashSet<String>()
        Regex("""(?:og:video(?::secure_url)?|contentUrl|embedUrl)"?\s*(?:content|=|:)\s*["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""(?:file|src|url|source)\s*[=:]\s*["'](https?://[^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        directStreamRegex.findAll(page).map { it.value }.forEach { links.add(it) }
        if (links.isEmpty()) {
            runCatching { JsUnpacker(page).takeIf { it.detect() }?.unpack() }.getOrNull()?.let { unpacked ->
                Regex("""(?:file|src)\s*[=:]\s*["'](https?://[^"']+)["']""")
                    .findAll(unpacked).map { it.groupValues[1] }.forEach { links.add(it) }
                directStreamRegex.findAll(unpacked).map { it.value }.forEach { links.add(it) }
            }
        }
        // lecteurs videojs obfusqués (vidzy.cc, fsvid.lol…) : src = XOR(base64, hostname)
        Regex("""\}\("([A-Za-z0-9+/=]{40,})"\)""").findAll(page).forEach { m ->
            val host = Regex("""^https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return@forEach
            decodeXorSource(m.groupValues[1], host)?.let { links.add(it) }
        }
        links.asSequence()
            .filter { it.startsWith("http") }
            .map { it.replace("&amp;", "&") }
            .filter { junkFilterRegex.containsMatchIn(it).not() }
            .filter { it.endsWith(".m3u8") || it.endsWith(".mp4") || it.endsWith(".webm") || directStreamRegex.matches(it) }
            .distinct()
            .forEach { link ->
                callback(
                    newExtractorLink(label, label, link) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                        this.type = if (".m3u8" in link) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }
        links.isNotEmpty()
    }.getOrDefault(false)

    /** vidzy/fsvid : sources videojs encodées XOR(base64 inversé, somme des codes du hostname). */
    private fun decodeXorSource(b64: String, hostname: String): String? = runCatching {
        val h = hostname.sumOf { it.code } and 0xFF
        val a = android.util.Base64.decode(b64, android.util.Base64.DEFAULT).reversed()
        val out = StringBuilder()
        for (i in a.indices) {
            val kk = (0x3d + i * 89 + h) and 0xFF
            out.append(((a[i].toInt() and 0xFF) xor kk).toChar())
        }
        out.toString().takeIf { it.startsWith("http") }
    }.getOrNull()
}

// Réponse Livewire standard
@JsonIgnoreProperties(ignoreUnknown = true)
data class LivewireComponent(
    val snapshot: String? = null,
    val effects: LivewireEffects? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LivewireEffects(val html: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LivewireResponse(val components: List<LivewireComponent> = emptyList())
