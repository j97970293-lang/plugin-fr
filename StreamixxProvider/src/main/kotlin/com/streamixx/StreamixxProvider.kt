package com.streamixx

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
import com.lagradost.cloudstream3.utils.Qualities

// ===========================================================================
// Streamixx — films & séries, MP4 directs multi-qualités + sous-titres
// ===========================================================================
// Site : https://www.streamixx.xyz (SPA React). L'application parle à une
// passerelle Cloudflare Worker publique, qui sert tout le catalogue :
//   GET {api}/api/homepage                    → topPickList (sélection)
//   GET {api}/api/trending?page=N&perPage=M   → subjectList (paged, hasMore)
//   GET {api}/api/search/{q}?page&perPage&type=0
//   GET {api}/api/info/{subjectId}            → subject + seasons[{se,maxEp}]
//   GET {api}/api/sources/{id}[?season=&episode=]
//        → downloads[{url, resolution, size}] + captions[{lan, lanName, url}]
//   GET {api}/api/caption?url=…               → sous-titres (VTT proxysé)
// Les liens sont des MP4 directs (CDN hakunaymatata, signés) — 360/480/720p.
// Les séries : seasons[].maxEp donne le nombre d'épisodes par saison.
// ===========================================================================
@CloudstreamPlugin
class StreamixxPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(StreamixxProvider())
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxCover(val url: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxSubject(
    val subjectId: String? = null,
    val subjectType: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val releaseDate: String? = null,
    val genre: String? = null,
    val cover: SmxCover? = null,
    val countryName: String? = null,
    val imdbRatingValue: String? = null,
    val duration: Long? = null,
    val subtitles: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxTrendingData(
    val subjectList: List<SmxSubject>? = null,
    val pager: Map<String, String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxSearchData(val items: List<SmxSubject>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxHomeData(val topPickList: List<SmxSubject>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxSeason(val se: Int? = null, val maxEp: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxResource(val seasons: List<SmxSeason>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxInfoData(
    val subject: SmxSubject? = null,
    val resource: SmxResource? = null,
    val isTvShow: Boolean? = null,
    val totalSeasons: Int? = null,
    val totalEpisodes: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxDownload(val url: String? = null, val resolution: Int? = null, val size: Long? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxCaption(val lan: String? = null, val lanName: String? = null, val url: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxSourcesData(val downloads: List<SmxDownload>? = null, val captions: List<SmxCaption>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxEnvelope<T>(val status: String? = null, val data: T? = null)

class StreamixxProvider : MainAPI() {
    override var mainUrl = "https://www.streamixx.xyz"
    override var name = "Streamixx"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiUrl = "https://mbx-core-gateway-v2.mymovieroom.workers.dev"

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private val mapper by lazy { com.fasterxml.jackson.databind.ObjectMapper() }

    override val mainPage = mainPageOf(
        "trending" to "🔥 Tendances",
        "top" to "⭐ Sélection"
    )

    private fun SmxSubject.toCard(): SearchResponse? {
        val id = subjectId ?: return null
        val t = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val isSeries = subjectType == 2
        val year = releaseDate?.take(4)?.toIntOrNull()
        return if (isSeries) {
            newTvSeriesSearchResponse(t, "smx:$id") {
                this.posterUrl = cover?.url
                this.year = year
            }
        } else {
            newMovieSearchResponse(t, "smx:$id") {
                this.posterUrl = cover?.url
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            "top" -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                val json = runCatching {
                    app.get("$apiUrl/api/homepage", headers = baseHeaders).text
                }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
                val data = runCatching {
                    AppUtils.parseJson<SmxEnvelope<SmxHomeData>>(json)
                }.getOrNull()?.data
                data?.topPickList.orEmpty().mapNotNull { it.toCard() }
            }
            else -> {
                val json = runCatching {
                    app.get("$apiUrl/api/trending?page=${page - 1}&perPage=36", headers = baseHeaders).text
                }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
                val env = runCatching { AppUtils.parseJson<SmxEnvelope<SmxTrendingData>>(json) }.getOrNull()
                env?.data?.subjectList.orEmpty().mapNotNull { it.toCard() }
            }
        }
        val hasMore = when (request.data) {
            "top" -> false
            else -> items.isNotEmpty()
        }
        return newHomePageResponse(request, items, hasNext = hasMore)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val json = runCatching {
            app.get("$apiUrl/api/search/$q?page=1&perPage=24&type=0", headers = baseHeaders).text
        }.getOrNull() ?: return emptyList()
        val env = runCatching { AppUtils.parseJson<SmxEnvelope<SmxSearchData>>(json) }.getOrNull()
        return env?.data?.items.orEmpty().mapNotNull { it.toCard() }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.removePrefix("smx:").substringBefore(':')
        val json = runCatching {
            app.get("$apiUrl/api/info/$id", headers = baseHeaders).text
        }.getOrNull() ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")
        val env = runCatching { AppUtils.parseJson<SmxEnvelope<SmxInfoData>>(json) }.getOrNull()
        val info = env?.data ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")
        val subject = info.subject ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")
        val title = subject.title?.trim().orEmpty()
        val year = subject.releaseDate?.take(4)?.toIntOrNull()
        val tags = subject.genre?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

        if (info.isTvShow != true) {
            return newMovieLoadResponse(title, url, TvType.Movie, "smx:$id") {
                this.posterUrl = subject.cover?.url
                this.year = year
                this.plot = subject.description?.takeIf { it.isNotBlank() }
                    ?: "Film ${subject.countryName ?: ""} ${subject.subtitles?.let { "— sous-titres : $it" } ?: ""}".trim()
                this.tags = tags
                this.score = subject.imdbRatingValue?.replace(",", ".")?.toDoubleOrNull()?.let { com.lagradost.cloudstream3.Score.from10(it) }
            }
        }

        val seasons = info.resource?.seasons.orEmpty().filter { (it.se ?: 1) >= 1 && (it.maxEp ?: 0) >= 1 }
        val episodes = mutableListOf<Episode>()
        seasons.forEach { s ->
            val se = s.se ?: 1
            val maxEp = s.maxEp ?: 0
            for (ep in 1..maxEp) {
                episodes += newEpisode("smx:$id:$se:$ep") {
                    this.name = "Épisode $ep"
                    this.season = se
                    this.episode = ep
                    this.posterUrl = subject.cover?.url
                }
            }
        }
        if (episodes.isEmpty()) {
            // saison 1 par défaut
            episodes += newEpisode("smx:$id:1:1") {
                this.name = "Épisode 1"
                this.season = 1
                this.episode = 1
                this.posterUrl = subject.cover?.url
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = subject.cover?.url
            this.year = year
            this.plot = subject.description?.takeIf { it.isNotBlank() }
                ?: "Série ${subject.countryName ?: ""} — ${info.totalSeasons ?: seasons.size} saison(s), ${info.totalEpisodes ?: episodes.size} épisode(s)".trim()
            this.tags = tags
            this.score = subject.imdbRatingValue?.replace(",", ".")?.toDoubleOrNull()?.let { com.lagradost.cloudstream3.Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.removePrefix("smx:").split(":")
        val id = parts.getOrNull(0) ?: return false
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        val qs = if (season != null && episode != null) "?season=$season&episode=$episode" else ""
        val json = runCatching {
            app.get("$apiUrl/api/sources/$id$qs", headers = baseHeaders).text
        }.getOrNull() ?: return false
        val env = runCatching { AppUtils.parseJson<SmxEnvelope<SmxSourcesData>>(json) }.getOrNull()
        val d = env?.data ?: return false
        var found = false
        d.downloads.orEmpty().forEach { dl ->
            val u = dl.url?.takeIf { it.startsWith("http") } ?: return@forEach
            found = true
            val res = dl.resolution ?: 0
            callback(
                ExtractorLink(
                    name, name + " · ${res}p", u, "$mainUrl/",
                    quality = when {
                        res >= 1080 -> Qualities.P1080.value
                        res >= 720 -> Qualities.P720.value
                        res >= 480 -> Qualities.P480.value
                        res >= 360 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    },
                    type = if (".m3u8" in u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        d.captions.orEmpty().forEach { cap ->
            val u = cap.url?.takeIf { it.startsWith("http") } ?: return@forEach
            val lang = cap.lan?.takeIf { it.isNotBlank() } ?: "en"
            runCatching {
                subtitleCallback(
                    SubtitleFile(
                        lang,
                        "$apiUrl/api/caption?url=${java.net.URLEncoder.encode(u, "UTF-8")}"
                    )
                )
            }
        }
        return found
    }
}
