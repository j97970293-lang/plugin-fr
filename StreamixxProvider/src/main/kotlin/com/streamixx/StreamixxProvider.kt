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
import com.lagradost.cloudstream3.network.CloudflareKiller
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
// Les liens sont des MP4 directs (CDN hakunaymatata, signés) — 360/480/720p.
// Les sous-titres sont des SRT signés (CloudFront) servis DIRECTEMENT par le
// CDN — ne pas passer par /api/caption (404 côté passerelle).
// ⚙ Réglages : si le site change de domaine ou de passerelle, l'URL est
// modifiable dans les réglages de l'extension ; la passerelle est aussi
// redécouverte automatiquement dans le bundle JS du site.
// ===========================================================================
@CloudstreamPlugin
class StreamixxPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        StreamixxProvider.appContext = context.applicationContext
        registerMainAPI(StreamixxProvider())
        openSettings = { ctx -> StreamixxProvider.showSettings(ctx) }
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

// ⚠️ NE PAS déclarer `pager` : ses valeurs sont mixtes (booléens + entiers),
// un Map<String,String> ferait échouer Jackson et viderait tout le catalogue.
@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxTrendingData(val subjectList: List<SmxSubject>? = null)

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

// size non déclaré : c'est une chaîne côté API et elle ne sert à rien ici
@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxDownload(val url: String? = null, val resolution: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxProcessed(
    val quality: Int? = null,
    val directUrl: String? = null,
    val streamUrl: String? = null,
    val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxCaption(val lan: String? = null, val lanName: String? = null, val url: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxSourcesData(
    val downloads: List<SmxDownload>? = null,
    val processedSources: List<SmxProcessed>? = null,
    val captions: List<SmxCaption>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmxEnvelope<T>(val status: String? = null, val data: T? = null)

class StreamixxProvider : MainAPI() {
    companion object {
        const val DEFAULT_URL = "https://www.streamixx.xyz"
        const val DEFAULT_API = "https://mbx-core-gateway-v2.mymovieroom.workers.dev"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "streamixx_settings"
        private const val PREF_URL = "site_url"
        private const val PREF_API = "api_url"

        fun currentUrl(): String = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                ?.getString(PREF_URL, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull() ?: DEFAULT_URL

        fun currentApi(): String = manualApi() ?: DEFAULT_API

        fun manualApi(): String? = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                ?.getString(PREF_API, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull()

        fun setUrls(context: android.content.Context, site: String?, api: String?) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, site?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .putString(PREF_API, api?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .apply()
        }

        fun showSettings(context: android.content.Context) {
            val inputSite = android.widget.EditText(context).apply {
                setText(currentUrl()); hint = "https://streamixx…"
            }
            val inputApi = android.widget.EditText(context).apply {
                setText(currentApi()); hint = "https://…workers.dev (vide = auto)"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(android.widget.TextView(context).apply { text = "Adresse du site" })
                addView(inputSite)
                addView(android.widget.TextView(context).apply {
                    text = "Passerelle API (laisser vide pour la détection automatique)"
                    setPadding(0, pad / 2, 0, 0)
                })
                addView(inputApi)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Réglages Streamixx")
                .setMessage("Si le site change de domaine ou de passerelle, indiquez les adresses actuelles.")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ ->
                    setUrls(
                        context,
                        inputSite.text.toString().ifBlank { DEFAULT_URL },
                        inputApi.text.toString()
                    )
                }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setUrls(context, DEFAULT_URL, DEFAULT_API) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "Streamixx"

    // La passerelle workers.dev peut être défiée par Cloudflare selon le réseau
    // (même cause que les autres sites : le défi passe via WebView).
    private val cfKiller by lazy { CloudflareKiller() }
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    private fun headers() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    /** Passerelle résolue SANS réseau : réglage manuel > cache (24 h) > défaut. */
    private fun apiUrl(): String {
        manualApi()?.let { return it }
        val cached = runCatching {
            val prefs = appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val ts = prefs?.getLong("discovered_ts", 0L) ?: 0L
            val gw = prefs?.getString("discovered_api", null)
            if (gw != null && System.currentTimeMillis() - ts < 24 * 3600_000L) gw else null
        }.getOrNull()
        return cached ?: DEFAULT_API
    }

    /** Invalide le cache et redécouvre la passerelle dans le bundle du site. */
    private suspend fun rediscoverApi(): String? {
        return runCatching {
            val prefs = appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            prefs?.edit()?.remove("discovered_api")?.remove("discovered_ts")?.apply()
            val home = app.get(mainUrl, headers = headers(), interceptor = cfKiller).text
            // le domaine peut rediriger (xyz → www.xyz) : suivre et utiliser l'URL finale
            val base = Regex("""https?://[^"\s]+""").find(home)?.let { _ -> mainUrl } ?: mainUrl
            // passerelle : sous-domaines multi-niveaux (a-b.c.workers.dev)
            val gwRegex = Regex("""https://[a-zA-Z0-9.-]+\.workers\.dev""")
            // chercher dans TOUS les bundles index-*.js référencés par la page
            val bundles = Regex("""(/assets/index-[A-Za-z0-9_-]+\.js)""").findAll(home).map { it.groupValues[1] }.distinct().toList()
            for (b in bundles) {
                val js = runCatching {
                    app.get("$base$b", headers = headers(), interceptor = cfKiller).text
                }.getOrNull() ?: continue
                gwRegex.find(js)?.value?.let { gw ->
                    runCatching {
                        prefs?.edit()?.putString("discovered_api", gw)
                            ?.putLong("discovered_ts", System.currentTimeMillis())?.apply()
                    }
                    return@runCatching gw
                }
            }
            null
        }.getOrNull()
    }

    /**
     * GET JSON avec rattrapage : si la passerelle résolue ne répond plus, on
     * invalide le cache, on redécouvre l'URL dans le bundle du site et on
     * réessaie ; en dernier recours on retente la valeur par défaut.
     */
    private suspend fun apiGet(path: String): String? {
        // le worker est instable (reset/429 aléatoires) : on cascade les
        // endpoints et on retente — direct, puis proxy moviex, puis
        // passerelle redécouverte, puis défaut.
        val candidates = linkedSetOf(apiUrl())
        val rediscovered = rediscoverApi()
        if (rediscovered != null) candidates.add(rediscovered)
        candidates.add(DEFAULT_API)
        for (base in candidates) {
            for (suffix in listOf("", "/api/proxy/moviex")) {
                val url = "$base$suffix$path"
                repeat(2) {
                    val r = runCatching {
                        app.get(url, headers = headers(), interceptor = cfKiller).text
                    }.getOrNull()
                    // une réponse d'erreur d'API n'est pas un succès : vérifier
                    if (r != null && !r.contains("\"status\":\"error\"")) return r
                }
            }
        }
        return null
    }

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
        syncUrl()
        val items: List<SearchResponse> = when (request.data) {
            "top" -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                val json = apiGet("/api/homepage")
                    ?: return newHomePageResponse(request, emptyList(), false)
                val data = runCatching {
                    AppUtils.parseJson<SmxEnvelope<SmxHomeData>>(json)
                }.getOrNull()?.data
                data?.topPickList.orEmpty().mapNotNull { it.toCard() }
            }
            else -> {
                val json = apiGet("/api/trending?page=${page - 1}&perPage=36")
                    ?: return newHomePageResponse(request, emptyList(), false)
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
        syncUrl()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val json = apiGet("/api/search/$q?page=1&perPage=24&type=0") ?: return emptyList()
        val env = runCatching { AppUtils.parseJson<SmxEnvelope<SmxSearchData>>(json) }.getOrNull()
        return env?.data?.items.orEmpty().mapNotNull { it.toCard() }
    }

    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val id = url.removePrefix("smx:").substringBefore(':')
        val json = apiGet("/api/info/$id")
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")
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
        syncUrl()
        val parts = data.removePrefix("smx:").split(":")
        val id = parts.getOrNull(0) ?: return false
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        val qs = if (season != null && episode != null) "?season=$season&episode=$episode" else ""
        val json = apiGet("/api/sources/$id$qs") ?: return false
        val env = runCatching { AppUtils.parseJson<SmxEnvelope<SmxSourcesData>>(json) }.getOrNull()
        val d = env?.data ?: return false
        var found = false
        fun q(res: Int?) = when {
            (res ?: 0) >= 1080 -> Qualities.P1080.value
            (res ?: 0) >= 720 -> Qualities.P720.value
            (res ?: 0) >= 480 -> Qualities.P480.value
            (res ?: 0) >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
        fun emit(u: String, res: Int?) {
            if (!u.startsWith("http")) return
            found = true
            callback(
                ExtractorLink(
                    name, name + " · ${res ?: "?"}p", u, "$mainUrl/",
                    quality = q(res),
                    type = if (".m3u8" in u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        // format actuel : processedSources (directUrl prioritaire sur le flux
        // proxysé) ; ancien format : downloads[].url
        d.processedSources.orEmpty().forEach { ps ->
            val u = ps.directUrl?.takeIf { it.startsWith("http") }
                ?: ps.streamUrl?.takeIf { it.startsWith("http") }
                ?: ps.url?.takeIf { it.startsWith("http") }
            if (u != null) emit(u, ps.quality)
        }
        if (!found) {
            d.downloads.orEmpty().forEach { dl ->
                emit(dl.url ?: return@forEach, dl.resolution)
            }
        }
        // Les sous-titres sont des SRT signés servis directement par le CDN
        // (le proxy /api/caption de la passerelle renvoie 404).
        d.captions.orEmpty().forEach { cap ->
            val u = cap.url?.takeIf { it.startsWith("http") } ?: return@forEach
            val lang = cap.lan?.takeIf { it.isNotBlank() } ?: "en"
            runCatching { subtitleCallback(SubtitleFile(lang, u)) }
        }
        return found
    }
}
