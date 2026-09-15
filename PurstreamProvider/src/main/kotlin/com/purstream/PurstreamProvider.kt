package com.purstream

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
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

// ===========================================================================
// Purstream (purstream.ad) — SPA React + API JSON ouverte
// ===========================================================================
// Analyse du site (14/09/2026, reverse du bundle /assets/index-*.js) :
//  · Base API : https://api.purstream.ad/api/v1/ (aucune authentification)
//  · Carrousels home : GET carousels/home → [{title, movies:{items:[…]}}]
//    (Action, Aventure, Animation, Animé, HUMOUR, DRAME, horreur — 48 films)
//  · Derniers ajouts : GET last-released-movies/{limit}
//  · Catalogue paginé : GET catalog/movies?page=N (≈5 000 titres,
//    items.data[] avec id/type/title/large_poster_path/release_date/runtime)
//  · Recherche : GET search-bar/search/{q} → items.movies.items[]
//  · Fiche film : GET media/{id}/sheet → {title, overview, posters,
//    categories, releaseDate, runtime, urls}
//  · Fiche série : GET media/{id}/seasons → [{season, episodes:[
//    {season, episode, name, poster, overview, airDate}]}]
//  · Sources : GET stream/{id} (film) ou stream/{id}/episode?season=N&episode=M
//    → items.sources[{stream_url, source_name, format}] — m3u8 HLS direct.
//  · L'URL publique des fiches : https://purstream.ad/{tvdbId|id}-slug — on
//    encode donc les données en "ps:{id}:{type}" (pas besoin du slug).
// ===========================================================================

/**
 * Point d'entrée du plugin — c'est CETTE classe que CloudStream charge.
 */
@CloudstreamPlugin
class PurstreamPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(PurstreamProvider())
    }
}

class PurstreamProvider : MainAPI() {

    override var mainUrl = "https://purstream.ad"
    override var name = "Purstream"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val api = "https://api.purstream.ad/api/v1/"

    private val apiHeaders = mapOf(
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
        val items: List<SearchResponse>
        var hasNext = false
        when {
            request.name == "recent" -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                val root = runCatching { getJson("last-released-movies/48") }.getOrNull()
                    ?: return newHomePageResponse(request, emptyList(), false)
                items = root.path("data").path("items").mapNotNull { it.toSearchResponse() }
            }
            request.name == "catalog" -> {
                val root = runCatching { getJson("catalog/movies?page=$page") }.getOrNull()
                    ?: return newHomePageResponse(request, emptyList(), false)
                val listNode = root.path("data").path("items")
                items = listNode.path("data").mapNotNull { it.toSearchResponse() }
                hasNext = listNode.path("current_page").asInt(0) < listNode.path("last_page").asInt(1)
            }
            else -> { // carousel:{titre}
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                val wanted = request.name.removePrefix("carousel:")
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
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val root = runCatching { getJson("search-bar/search/$q") }.getOrNull() ?: return emptyList()
        return root.path("data").path("items").path("movies").path("items")
            .mapNotNull { it.toSearchResponse() }
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        // url = ps:{id}:{movie|tv}
        val parts = url.split(":")
        if (parts.size < 3) throw ErrorLoadingException("URL interne invalide")
        val id = parts[1]
        val type = parts[2]

        val sheet = runCatching { getJson("media/$id/sheet") }.getOrNull()
            ?: throw ErrorLoadingException("Fiche inaccessible")
        val it = sheet.path("data").path("items")
        val title = it.path("title").asText("Purstream $id")
        val poster = it.path("posters").path("large").asText(null)?.takeIf { it.isNotBlank() }
            ?: it.path("posters").path("small").asText(null)
        val plot = it.path("overview").asText(null)?.takeIf { it.isNotBlank() }
        val year = it.path("releaseDate").asText(null)?.take(4)?.toIntOrNull()
        val genres = it.path("categories").mapNotNull { c -> c.path("name").asText(null) }.take(8)

        if (type == "tv") {
            val seasonsRoot = runCatching { getJson("media/$id/seasons") }.getOrNull()
                ?: throw ErrorLoadingException("Saisons indisponibles")
            val episodes = mutableListOf<Episode>()
            seasonsRoot.path("data").path("items").forEach { season ->
                season.path("episodes").forEach { ep ->
                    val n = ep.path("episode").asInt(0)
                    val s = ep.path("season").asInt(season.path("season").asInt(1))
                    if (n > 0) {
                        episodes += newEpisode("ps:$id:tv:$s:$n") {
                            this.season = s
                            this.episode = n
                            this.name = ep.path("name").asText(null)?.takeIf { it.isNotBlank() }
                            this.description = ep.path("overview").asText(null)?.takeIf { it.isNotBlank() }
                            // vignette d'épisode TMDB fournie par l'API
                            this.posterUrl = ep.path("poster").asText(null)?.takeIf { p -> p.isNotBlank() }
                                ?: poster
                        }
                    }
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

        return newMovieLoadResponse(title, url, TvType.Movie, "ps:$id:movie") {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    // -------------------------------------------------------------------------
    // Lecture
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = ps:{id}:movie | ps:{id}:tv:{season}:{episode}
        val parts = data.split(":")
        if (parts.size < 3) return false
        val id = parts[1]
        val endpoint = when {
            parts[2] == "tv" && parts.size >= 5 ->
                "stream/$id/episode?season=${parts[3]}&episode=${parts[4]}"
            else -> "stream/$id"
        }
        val root = runCatching { getJson(endpoint) }.getOrNull() ?: return false
        val sources = root.path("data").path("items").path("sources")
        if (!sources.isArray || sources.size() == 0) return false
        sources.forEach { src ->
            val u = src.path("stream_url").asText(null) ?: return@forEach
            if (!u.startsWith("http")) return@forEach
            val label = src.path("source_name").asText("Source").ifBlank { "Source" }
            callback(
                newExtractorLink(label, label, u) {
                    this.type = ExtractorLinkType.M3U8
                    this.referer = "$mainUrl/"
                }
            )
        }
        return true
    }

    // -------------------------------------------------------------------------
    // Outils
    // -------------------------------------------------------------------------
    private val mapper by lazy { com.fasterxml.jackson.databind.ObjectMapper() }

    private suspend fun getJson(path: String): JsonNode? =
        runCatching { mapper.readTree(app.get(api + path, headers = apiHeaders).text) }.getOrNull()

    private fun JsonNode.toSearchResponse(): SearchResponse? {
        val id = path("id").asInt(0)
        if (id <= 0) return null
        val t = path("title").asText(null)?.trim() ?: return null
        val poster = path("large_poster_path").asText(null)
            ?: path("posters").path("large").asText(null)
        val url = "ps:$id:" + if (path("type").asText("movie") == "tv") "tv" else "movie"
        return if (path("type").asText("movie") == "tv") {
            newTvSeriesSearchResponse(t, url, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(t, url, TvType.Movie) { this.posterUrl = poster }
        }
    }
}
