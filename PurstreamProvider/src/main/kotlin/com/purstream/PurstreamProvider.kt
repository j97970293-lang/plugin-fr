package com.purstream

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// ===========================================================================
// Purstream (purstream.ad) — SPA React + API JSON ouverte
// ===========================================================================
// Analyse du site (15/09/2026, reverse du bundle /assets/index-*.js) :
//  · Base API : https://api.purstream.ad/api/v1/ (aucune authentification)
//  · Carrousels home : GET carousels/home → [{title, movies:{items:[…]}}]
//    (Action, Aventure, Animation, Animé, HUMOUR, DRAME, horreur — 48 films)
//  · Derniers ajouts : GET last-released-movies/{limit}
//  · Catalogue paginé : GET catalog/movies?page=N (≈5 000 titres,
//    items.data[] avec id/type/title/large_poster_path/release_date/runtime)
//  · Recherche : GET search-bar/search/{q} → items.movies.items[]
//  · Fiche : GET media/{id}/sheet → {title, overview, posters, categories,
//    releaseDate, runtime, tmdbId, urls[]}
//  · Fiche série : GET media/{id}/seasons → [{season, episodes:[
//    {season, episode, name, poster, overview, airDate}]}]
//    ⚠ INCOMPLET pour certains animes (Naruto : S1 seule) alors que
//    sheet.urls contient TOUT (Naruto S1→S4, 220 ép.) → UNION des deux.
//    La numérotation de urls est CONTINUE (S2 = E53-104) et c'est celle
//    qu'attend l'API stream (S2E1 → 404, S2E53 → 200).
//  · Sources : GET stream/{id} (film) ou stream/{id}/episode?season=N&episode=M
//    → items.sources[{stream_url, source_name, format}] — m3u8 HLS direct.
//  · L'URL publique des fiches : https://purstream.ad/{tvdbId|id}-slug — on
//    encode donc les données en "ps:{id}:{type}" (pas besoin du slug).
//  · Le champ tmdbId de la fiche permet d'ajouter des lecteurs publics
//    ( Videasy, VidFast, VidSrc… ) et l'agrégateur apiwiflix en secours.
// ===========================================================================

/**
 * Point d'entrée du plugin — c'est CETTE classe que CloudStream charge.
 */
@CloudstreamPlugin
class PurstreamPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        PurstreamProvider.appContext = context.applicationContext
        registerMainAPI(PurstreamProvider())
        // bouton ⚙ dans CloudStream → Paramètres → Extensions → Purstream
        openSettings = { ctx -> PurstreamProvider.showSettings(ctx) }
    }
}

class PurstreamProvider : MainAPI() {

    companion object {
        const val DEFAULT_URL = "https://purstream.ad"
        const val DEFAULT_API = "https://api.purstream.ad/api/v1/"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "purstream_settings"
        private const val PREF_URL = "site_url"
        private const val PREF_API = "api_url"

        private fun readPref(key: String): String? = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                ?.getString(key, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull()

        fun currentUrl(): String = readPref(PREF_URL) ?: DEFAULT_URL

        fun currentApi(): String = (readPref(PREF_API) ?: DEFAULT_API)
            .let { if (it.endsWith("/")) it else "$it/" }

        private fun writePrefs(context: android.content.Context, site: String?, api: String?) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, site?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .putString(PREF_API, api?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .apply()
        }

        fun showSettings(context: android.content.Context) {
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val inputSite = android.widget.EditText(context).apply {
                setText(currentUrl()); hint = "https://purstream.ad"
            }
            val inputApi = android.widget.EditText(context).apply {
                setText(currentApi().trimEnd('/')); hint = "https://api.purstream.ad/api/v1/"
            }
            val layout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(android.widget.TextView(context).apply { text = "Adresse du site" })
                addView(inputSite)
                addView(android.widget.TextView(context).apply {
                    text = "Adresse de l'API"; setPadding(0, pad / 2, 0, 0)
                })
                addView(inputApi)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Purstream")
                .setMessage("Le site change de domaine de temps en temps. Indiquez les adresses actuelles (site web et API).")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ ->
                    writePrefs(context, inputSite.text.toString(), inputApi.text.toString())
                }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ ->
                    writePrefs(context, DEFAULT_URL, DEFAULT_API)
                }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "Purstream"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    private val apiHeaders get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    // -------------------------------------------------------------------------
    // Accueil
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "recent" to "Derniers ajouts",
        "catalog" to "Catalogue",
        "carousel:Action" to "Action",
        "carousel:Aventure" to "Aventure",
        "carousel:Animation" to "Animation",
        "carousel:Animé" to "Animés",
        "carousel:HUMOUR" to "Comédie",
        "carousel:DRAME" to "Drame",
        "carousel:horreur" to "Horreur"
    )

    /** Cache court des carrousels (une seule requête pour toutes les sections). */
    private var carouselsCache: Pair<Long, List<Pair<String, List<JsonNode>>>>? = null

    private suspend fun fetchCarousels(): List<Pair<String, List<JsonNode>>> {
        val cached = carouselsCache
        if (cached != null && System.currentTimeMillis() - cached.first < 120_000L) return cached.second
        val root = runCatching { getJson("carousels/home") }.getOrNull() ?: return emptyList()
        val out = root.path("data").path("items").map { carousel ->
            carousel.path("title").asText("") to carousel.path("movies").path("items").map { it }
        }.filter { it.first.isNotBlank() && it.second.isNotEmpty() }
        carouselsCache = System.currentTimeMillis() to out
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val items: List<SearchResponse>
        var hasNext = false
        when {
            request.data == "recent" -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                val root = runCatching { getJson("last-released-movies/48") }.getOrNull()
                    ?: return newHomePageResponse(request, emptyList(), false)
                items = root.path("data").path("items").mapNotNull { it.toSearchResponse() }
            }
            request.data == "catalog" -> {
                val root = runCatching { getJson("catalog/movies?page=$page") }.getOrNull()
                    ?: return newHomePageResponse(request, emptyList(), false)
                val listNode = root.path("data").path("items")
                items = listNode.path("data").mapNotNull { it.toSearchResponse() }
                hasNext = listNode.path("current_page").asInt(0) < listNode.path("last_page").asInt(1)
            }
            else -> { // carousel:{titre}
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                val wanted = request.data.removePrefix("carousel:")
                val carousel = fetchCarousels().firstOrNull { it.first.equals(wanted, ignoreCase = true) }
                    ?: return newHomePageResponse(request, emptyList(), false)
                items = carousel.second.mapNotNull { it.toSearchResponse() }
            }
        }
        return newHomePageResponse(request, items, hasNext)
    }

    // -------------------------------------------------------------------------
    // Recherche
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val root = runCatching { getJson("search-bar/search/$q") }.getOrNull() ?: return emptyList()
        return root.path("data").path("items").path("movies").path("items")
            .mapNotNull { it.toSearchResponse() }
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    // Les fiches utilisent de vraies URLs (https://purstream.ad/movie/{id} ou
    // /serie/{id}) : CloudStream les manipule sans souci, l'identifiant est le
    // dernier segment du chemin.
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val id = url.trimEnd('/').substringAfterLast('/').toIntOrNull()
            ?: throw ErrorLoadingException("Fiche introuvable")
        val type = if (url.contains("/serie/")) "tv" else "movie"

        val sheet = runCatching { getJson("media/$id/sheet") }.getOrNull()
            ?: throw ErrorLoadingException("Fiche inaccessible")
        val it = sheet.path("data").path("items")
        val title = it.path("title").asText("Purstream $id")
        val poster = it.path("posters").path("large").asText(null)?.takeIf { it.isNotBlank() }
            ?: it.path("posters").path("small").asText(null)
        val plot = it.path("overview").asText(null)?.takeIf { it.isNotBlank() }
        val year = it.path("releaseDate").asText(null)?.take(4)?.toIntOrNull()
        val genres = it.path("categories").mapNotNull { c -> c.path("name").asText(null) }.take(8)
        val tmdb = it.path("tmdbId").asInt(0).takeIf { t -> t > 0 }

        if (type == "tv") {
            // ---- UNION sheet.urls ∪ media/{id}/seasons ----
            // seasons = métadonnées (noms, vignettes, résumés) mais est souvent
            // incomplet (Naruto : S1 seule) ; sheet.urls liste TOUT ce qui est
            // lisible, avec la numérotation continue attendue par l'API stream.
            data class EpMeta(val name: String?, val overview: String?, val poster: String?)
            val metas = mutableMapOf<Pair<Int, Int>, EpMeta>()
            val seasonsRoot = runCatching { getJson("media/$id/seasons") }.getOrNull()
            seasonsRoot?.path("data")?.path("items")?.forEach { season ->
                val sDef = season.path("season").asInt(1)
                season.path("episodes").forEach { ep ->
                    val n = ep.path("episode").asInt(0)
                    val s = ep.path("season").asInt(sDef)
                    if (n > 0 && s > 0) {
                        metas[s to n] = EpMeta(
                            ep.path("name").asText(null)?.takeIf { x -> x.isNotBlank() },
                            ep.path("overview").asText(null)?.takeIf { x -> x.isNotBlank() },
                            ep.path("poster").asText(null)?.takeIf { p -> p.isNotBlank() }
                        )
                    }
                }
            }

            val keys = java.util.TreeSet<Pair<Int, Int>>(compareBy({ it.first }, { it.second }))
            // 1) sheet.urls (source la plus complète — numérotation continue)
            val urlEntryRe = Regex("""/S(\d+)/E(\d+)/""")
            it.path("urls").forEach { u ->
                val m = urlEntryRe.find(u.path("url").asText("")) ?: return@forEach
                val s = m.groupValues[1].toIntOrNull() ?: return@forEach
                val e = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (s > 0 && e > 0) keys.add(s to e)
            }
            // 2) seasons : ajoute les épisodes que sheet.urls n'aurait pas
            metas.keys.forEach { keys.add(it) }

            val episodes = keys.map { (s, e) ->
                val meta = metas[s to e]
                newEpisode(episodeDataUrl(id, s, e, tmdb)) {
                    this.season = s
                    this.episode = e
                    this.name = meta?.name
                    this.description = meta?.overview
                    this.posterUrl = meta?.poster ?: poster
                }
            }

            if (episodes.isEmpty()) throw ErrorLoadingException("Aucun épisode disponible")
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                this.showStatus = ShowStatus.Ongoing
            }
        }

        return newMovieLoadResponse(
            title, url, TvType.Movie,
            if (tmdb != null) "$mainUrl/play/movie/$id?t=$tmdb" else "$mainUrl/play/movie/$id"
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    private fun episodeDataUrl(id: Int, s: Int, e: Int, tmdb: Int?): String =
        if (tmdb != null) "$mainUrl/play/tv/$id/$s/$e?t=$tmdb" else "$mainUrl/play/tv/$id/$s/$e"

    // -------------------------------------------------------------------------
    // Lecture
    // -------------------------------------------------------------------------
    // data = https://purstream.ad/play/movie/{id}[?t={tmdb}]
    //      | https://purstream.ad/play/tv/{id}/{season}/{episode}[?t={tmdb}]
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val tmdb = Regex("""[?&]t=(\d+)""").find(data)?.groupValues?.get(1)
        val clean = data.substringBefore('?')
        val segments = clean.trimEnd('/').split("/").filter { it.isNotBlank() }
        // …/play/{movie|tv}/{id}[/{season}/{episode}]
        val playIdx = segments.indexOfLast { it == "play" }
        if (playIdx < 0) return false
        val kind = segments.getOrNull(playIdx + 1) ?: return false
        val id = segments.getOrNull(playIdx + 2) ?: return false
        var season: Int? = null
        var episode: Int? = null
        val endpoint = when (kind) {
            "tv" -> {
                season = segments.getOrNull(playIdx + 3)?.toIntOrNull() ?: return false
                episode = segments.getOrNull(playIdx + 4)?.toIntOrNull() ?: return false
                "stream/$id/episode?season=$season&episode=$episode"
            }
            else -> "stream/$id"
        }

        var produced = 0

        // ---- 1) Sources du site (m3u8 HLS directs) ----
        runCatching {
            val root = getJson(endpoint) ?: return@runCatching
            val sources = root.path("data").path("items").path("sources")
            sources.forEach { src ->
                val u = src.path("stream_url").asText(null) ?: return@forEach
                if (!u.startsWith("http")) return@forEach
                val label = src.path("source_name").asText("Source").ifBlank { "Source" }
                produced++
                callback(
                    newExtractorLink(label, label, u) {
                        this.type = ExtractorLinkType.M3U8
                        this.referer = "$mainUrl/"
                    }
                )
            }
        }

        // ---- 2) Lecteurs publics TMDB + agrégateur apiwiflix (serveurs en plus) ----
        if (tmdb != null) {
            val embeds = buildList {
                add(
                    if (season != null)
                        "https://player.videasy.net/tv/$tmdb/$season/$episode?overlay=true&color=8B5CF6&nextEpisode=true&episodeSelector=true" to "Videasy"
                    else "https://player.videasy.net/movie/$tmdb?overlay=true&color=8B5CF6" to "Videasy"
                )
                add(
                    if (season != null)
                        "https://frembed.skin/embed/serie/$tmdb?sa=$season&epi=$episode" to "Frembed"
                    else "https://frembed.skin/embed/movie/$tmdb" to "Frembed"
                )
                add(
                    if (season != null)
                        "https://peachify.top/embed/tv/$tmdb/$season/$episode?dub=French&sub=French&autoNext=30" to "Peachify"
                    else "https://peachify.top/embed/movie/$tmdb?dub=French&sub=French" to "Peachify"
                )
                addAll(
                    if (season != null) listOf(
                        "https://vidfast.pro/tv/$tmdb/$season/$episode?autoPlay=true&sub=fr" to "VidFast",
                        "https://vidsrc.cc/v2/embed/tv/$tmdb/$season/$episode" to "VidSrc.cc",
                        "https://www.vidsrc.wtf/api/2/tv/?id=$tmdb&s=$season&e=$episode" to "VidSrc.wtf",
                        "https://www.2embed.cc/embedtv/$tmdb&s=$season&e=$episode" to "2Embed",
                        "https://111movies.com/tv/$tmdb/$season/$episode" to "111Movies",
                        "https://www.braflix.win/watch/$tmdb?s=$season&e=$episode" to "Braflix",
                        "https://www.vidking.net/embed/tv/$tmdb/$season/$episode?autoPlay=true" to "VidKing",
                        "https://vidnest.fun/tv/$tmdb/$season/$episode" to "VidNest"
                    ) else listOf(
                        "https://vidfast.pro/movie/$tmdb?autoPlay=true&sub=fr" to "VidFast",
                        "https://vidsrc.cc/v2/embed/movie/$tmdb" to "VidSrc.cc",
                        "https://www.vidsrc.wtf/api/3/movie/?id=$tmdb" to "VidSrc.wtf",
                        "https://www.2embed.cc/embed/$tmdb" to "2Embed",
                        "https://111movies.com/movie/$tmdb" to "111Movies",
                        "https://www.braflix.win/watch/$tmdb" to "Braflix",
                        "https://www.vidking.net/embed/movie/$tmdb?autoPlay=true" to "VidKing",
                        "https://vidnest.fun/movie/$tmdb" to "VidNest"
                    )
                )
            }

            // Agrégateur apiwiflix : liens hébergeurs AVEC langue (VF/VOSTFR)
            val aggregatorLinks = runCatching {
                val url = if (season != null && episode != null) {
                    "https://apis.wavewatch.top/apiwiflix.php?id=$tmdb&season=$season&episode=$episode"
                } else {
                    "https://apis.wavewatch.top/apiwiflix.php?id=$tmdb"
                }
                val html = app.get(url, headers = apiHeaders).text
                val m = Regex("""allSources\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
                m?.let {
                    mapper.readTree(it.groupValues[1]).mapNotNull { s ->
                        s.path("url").asText(null)?.takeIf { u -> u.startsWith("http") }?.let { u ->
                            val lang = s.path("language").asText(null)?.trim()?.uppercase()
                                ?.replace("FRENCH", "VF")?.takeIf { l -> l.isNotBlank() }
                            val nm = s.path("name").asText("Lecteur")
                            Triple(u, nm, lang)
                        }
                    }
                } ?: emptyList()
            }.getOrDefault(emptyList())

            // Extraction parallèle (limitée) sur tous les embeds
            val toExtract = embeds.map { it.first to it.second } +
                aggregatorLinks.map { it.first to (it.second + (it.third?.let { l -> " · $l" } ?: "")) }
            val semaphore = Semaphore(6)
            val producedExtra = java.util.concurrent.atomic.AtomicInteger(0)
            coroutineScope {
                toExtract.map { (u, label) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            runCatching {
                                loadExtractor(u, mainUrl, { sub -> subtitleCallback(sub) }) { link ->
                                    val nm = "Purstream+ · $label"
                                    producedExtra.incrementAndGet()
                                    callback(
                                        com.lagradost.cloudstream3.utils.ExtractorLink(
                                            nm, nm,
                                            link.url, link.referer, link.quality,
                                            link.headers, link.extractorData, link.type
                                        )
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            produced += producedExtra.get()
        }

        return produced > 0
    }

    // -------------------------------------------------------------------------
    // Outils
    // -------------------------------------------------------------------------
    private val mapper by lazy { com.fasterxml.jackson.databind.ObjectMapper() }

    private suspend fun getJson(path: String): JsonNode? =
        runCatching { mapper.readTree(app.get(currentApi() + path, headers = apiHeaders).text) }.getOrNull()

    private fun JsonNode.toSearchResponse(): SearchResponse? {
        val id = path("id").asInt(0)
        if (id <= 0) return null
        val t = path("title").asText(null)?.trim() ?: return null
        val poster = path("large_poster_path").asText(null)
            ?: path("posters").path("large").asText(null)
        val isTv = path("type").asText("movie") == "tv"
        val url = if (isTv) "$mainUrl/serie/$id" else "$mainUrl/movie/$id"
        return if (isTv) {
            newTvSeriesSearchResponse(t, url, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(t, url, TvType.Movie) { this.posterUrl = poster }
        }
    }
}
