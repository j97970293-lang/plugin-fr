package com.afterdark

import android.content.Context
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Entry point of the plugin, this is the class that CloudStream loads.
 */
@CloudstreamPlugin
class AfterdarkPlugin : Plugin() {
    override fun load(context: Context) {
        AfterdarkProvider.appContext = context.applicationContext
        registerMainAPI(AfterdarkProvider())
        // Lecteurs : 1embed.cc (playlists HLS) + ansembed.net ( JWPlayer, secours AnimoFlix )
        registerExtractorAPI(OneEmbed())
        registerExtractorAPI(AnsEmbed())
        registerExtractorAPI(Vidara())
        // Réglages : bouton « Réglages » sur la fiche de l'extension dans CloudStream
        openSettings = { ctx -> AfterdarkProvider.showSettings(ctx) }
    }
}

/**
 * ansembed.net — hébergeur JWPlayer (clone VidMoly) utilisé par AnimoFlix.
 * Extrait `sources: [{ file: 'https://…/master.m3u8' }]` de la page d'embed.
 */
class AnsEmbed : ExtractorApi() {
    override val name = "AnsEmbed"
    override val mainUrl = "https://ansembed.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "user-agent" to USER_AGENT,
            "Sec-Fetch-Dest" to "iframe"
        )
        val document = app.get(url, headers = headers, referer = referer).document
        val script = document.select("script")
            .firstOrNull { it.data().contains("sources:") }
            ?.data()
            ?: throw ErrorLoadingException("No JWPlayer sources found")
        com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
            .extractStreamLinks(script, name, mainUrl, callback, subtitleCallback)
    }
}

/**
 * 1embed.cc — lecteur dont la page embarque la liste de ses serveurs en JSON :
 *  - nouveau format : {"name":"Necro","url":"https://abdx.tv/hls-proxy?url=…&referer=…","type":"hls"}
 *    (le proxy renvoie une playlist HLS valide, vérifié)
 *  - ancien format : {"name":"Solari","url":"/v/solari_<id>.m3u8","type":"hls"}
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
        // Un-escape le payload Next.js (\" -> " et \u0026 -> &)
        val norm = html.replace("\\\"", "\"").replace("\\u0026", "&")
        val servers = LinkedHashMap<String, String>()
        Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""")
            .findAll(norm).forEach {
                val serverName = it.groupValues[1]
                val serverUrl = it.groupValues[2]
                // Nouveau format : proxy HLS ; ancien : chemin /v/*.m3u8 relatif
                if ("hls-proxy" in serverUrl || ".m3u8" in serverUrl) {
                    if (serverUrl !in servers) servers[serverUrl] = serverName
                }
            }
        // Ancien format : n'importe quel chemin /v/*.m3u8 dans la page
        if (servers.isEmpty()) {
            Regex(""""(/v/[a-zA-Z0-9_.-]+\.m3u8)"""").findAll(norm).forEach {
                if (it.groupValues[1] !in servers) servers[it.groupValues[1]] = "Serveur"
            }
        }
        servers.forEach { (serverUrl, serverName) ->
            val absolute = if (serverUrl.startsWith("http")) serverUrl else "$mainUrl$serverUrl"
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
 * vidara.to — hébergeur type Vidmoly (liens apiwiflix/zeus, affiché « Vidara »).
 * L'extracteur intégré de CloudStream marque TOUT en HLS sans vérifier : quand le
 * fichier est un MP4 ou un lien mort, le lecteur plante avec l'erreur 3003
 * « container unsupported ». Ici on interroge /api/stream (POST JSON) et on ne
 * marque HLS que si l'URL est vraiment un .m3u8 ; parse défensif (regex) car
 * « subtitles » peut valoir la chaîne "None" et faire planter un parseur strict.
 */
class Vidara : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = url.substringAfterLast("/").substringBefore("?")
        val res = runCatching {
            app.post(
                "$mainUrl/api/stream",
                json = mapOf("filecode" to fileCode, "device" to "web"),
                referer = mainUrl
            ).text
        }.getOrNull() ?: return
        val stream = Regex("\"streaming_url\"\\s*:\\s*\"([^\"]+)\"")
            .find(res)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
        if (!stream.startsWith("http")) return
        callback(
            newExtractorLink(name, name, stream) {
                this.type = if (".m3u8" in stream) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                this.referer = mainUrl
            }
        )
        // Sous-titres : le champ vaut parfois la chaîne "None" — on ne parse que les vrais tableaux
        val arr = Regex("\"subtitles\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(res)?.groupValues?.get(1) ?: return
        val paths = Regex("\"file_path\"\\s*:\\s*\"([^\"]+)\"").findAll(arr)
            .map { it.groupValues[1].replace("\\/", "/") }.toList()
        val langs = Regex("\"language\"\\s*:\\s*\"([^\"]+)\"").findAll(arr)
            .map { it.groupValues[1] }.toList()
        paths.zip(langs).forEach { (path, lang) ->
            subtitleCallback(SubtitleFile(lang, path))
        }
    }
}

/**
 * Extension CloudStream pour Afterdark (https://afd926.mom — canonique : afterdark.best)
 *
 * Site (vérifié par reverse-engineering des bundles JS + requêtes réelles) :
 *  · React + TanStack Start, catalogue TMDB (proxy serveur, données FR)
 *  · Fiches : /title/{type}-{tmdbId} ; lecteur : /watch/{type}-{tmdbId}?s=&e=
 *  · API de flux maison : GET /api/sources (NDJSON, groupes seoul/pekin) protégée
 *    par Cloudflare Turnstile — un « proof » (x-nabi-proof) est exigé, vérifié
 *    côté serveur (403 sans proof, POST de token invalide → ok:false). Le primaire
 *    du site n'est donc PAS utilisable sans WebView/captcha.
 *
 * L'extension livre le contenu du site autrement :
 *  · Catalogue : API TMDB publique en français (le même proxy public que
 *    l'extension WaveWatch — les données TMDB sont identiques).
 *  · Lecture : les 3 lecteurs publics de secours du site lui-même (videasy,
 *    frembed.skin, peachify — URL exactes tirées de son bundle) + un arsenal
 *    d'agrégateurs TMDB en parallèle : apiwiflix, playerix (HLS), zeus (SSE),
 *    movix, french-stream, 1embed + les épisodes d'animes d'AnimoFlix
 *    (utile pour les derniers épisodes de One Piece & co, que les agrégateurs
 *    n'ont pas) — soit 40 à 60+ liens par contenu, avec la langue (VOSTFR
 *    d'abord) affichée sur chaque lien.
 */
class AfterdarkProvider : MainAPI() {

    override var mainUrl = DEFAULT_URL
    override var name = "Afterdark"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    /** Catalogue : proxy TMDB public (les identifiants restent des ID TMDB). */
    private val tmdbProxy = "https://wavewatch.top/api/tmdb"

    override val mainPage = mainPageOf(
        "tmdb:/trending/movies" to "🔥 Films Tendance",
        "tmdb:/trending/tv" to "📺 Séries Tendance",
        "tmdb:/upcoming/movies" to "📅 Prochaines Sorties",
        "paged:/discover/movie?genre=28" to "💥 Action",
        "paged:/discover/movie?genre=878" to "🚀 Science-Fiction",
        "paged:/discover/movie?genre=53" to "🔪 Thriller",
        "paged:/discover/movie?genre=16" to "✨ Animation",
        "paged:/discover/movie?genre=27" to "👻 Horreur",
        "paged:/discover/movie?genre=35" to "😂 Comédie",
        "paged:/discover/tv?genre=10759" to "🗡️ Séries Action & Aventure",
        "paged:/discover/tv?genre=18" to "🎭 Séries Drame",
    )

    private val baseHeaders = mapOf("user-agent" to USER_AGENT)
    private val embedHeaders get() = mapOf("user-agent" to USER_AGENT, "referer" to "$mainUrl/")

    private val tmdbImage = "https://image.tmdb.org/t/p"

    /** Un lien d'hébergeur à passer aux extracteurs, avec sa langue quand elle est connue. */
    private data class HostLink(
        val url: String,
        val lang: String? = null,
        val label: String,   // utilisé si l'extraction générique produit le lien
        val origin: String,  // d'où vient le lien (tri)
        val direct: Boolean = false, // playlist HLS directement jouable
        val quality: Int = Qualities.Unknown.value
    )

    // -------------------------------------------------------------------------
    // Réglages : adresse du site modifiable (miroirs / changement de domaine)
    // -------------------------------------------------------------------------
    companion object {
        const val DEFAULT_URL = "https://afd926.mom"

        @Volatile
        var appContext: Context? = null

        private const val PREFS_NAME = "afterdark_settings"
        private const val PREF_URL = "site_url"

        /** Adresse actuelle : réglage utilisateur si défini, sinon celle par défaut. */
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

        /** Bouton « Réglages » de l'extension : boîte de dialogue pour changer l'adresse. */
        fun showSettings(context: Context) {
            val input = android.widget.EditText(context).apply {
                setText(currentUrl())
                hint = "https://…"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(
                    android.widget.TextView(context).apply {
                        text = "Adresse du site Afterdark (à changer s'il déménage) :"
                    }
                )
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Afterdark")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ ->
                    val value = input.text.toString().trim()
                    if (value.startsWith("http")) {
                        setSiteUrl(context, value)
                        toast(context, "Adresse enregistrée : $value")
                    } else {
                        toast(context, "Adresse invalide : elle doit commencer par https://")
                    }
                }
                .setNeutralButton("Par défaut") { _, _ ->
                    setSiteUrl(context, null)
                    toast(context, "Adresse par défaut restaurée : $DEFAULT_URL")
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        private fun toast(context: Context, message: String) =
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Applique l'adresse personnalisée à chaque requête. */
    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    // -------------------------------------------------------------------------
    // Page d'accueil (sections TMDB en français)
    // -------------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val data = request.data
        val paged = data.startsWith("paged:")
        if (!paged && page > 1) return newHomePageResponse(request.name, emptyList())
        val path = data.removePrefix("tmdb:").removePrefix("paged:")
        val separator = if ("?" in path) "&" else "?"
        val url = "$tmdbProxy$path${separator}page=$page"
        val list = runCatching {
            AppUtils.parseJson<WwTmdbPage>(app.get(url, headers = baseHeaders).text)
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        val isTv = "/tv" in path
        val cards = list.results.mapNotNull { tmdbCard(it, isTv) }
        return newHomePageResponse(request.name, cards.distinctBy { it.url })
    }

    private fun tmdbCard(item: WwTmdbItem, isTv: Boolean): SearchResponse? {
        val title = (item.title ?: item.name)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = item.posterPath?.let { "$tmdbImage/w500$it" }
        val year = (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull()
        return if (isTv || item.mediaType == "tv") {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/${item.id}", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = item.voteAverage?.let { Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/${item.id}", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = item.voteAverage?.let { Score.from10(it) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Recherche (multi : films + séries)
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        return runCatching {
            val json = app.get(
                "$tmdbProxy/search",
                params = mapOf("q" to trimmed),
                headers = baseHeaders
            ).text
            AppUtils.parseJson<WwTmdbPage>(json).results
                .mapNotNull { tmdbCard(it, isTv = it.mediaType == "tv") }
        }.getOrDefault(emptyList())
    }

    // -------------------------------------------------------------------------
    // Détail
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val tmdb = Regex("""/(movie|tv)/(\d+)""").find(url)?.groupValues?.get(2)
            ?: throw ErrorLoadingException("URL non reconnue : $url")

        if ("/movie/" in url) {
            val detail = AppUtils.parseJson<WwMovieDetail>(
                app.get("$tmdbProxy/movie/$tmdb", headers = baseHeaders).text
            )
            val title = detail.title?.trim()?.takeIf { it.isNotBlank() } ?: "Film"
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = detail.posterPath?.let { "$tmdbImage/w500$it" }
                this.backgroundPosterUrl = detail.backdropPath?.let { "$tmdbImage/original$it" }
                this.year = detail.releaseDate?.take(4)?.toIntOrNull()
                this.score = detail.voteAverage?.let { Score.from10(it) }
                this.plot = detail.overview?.trim()?.takeIf { it.isNotBlank() }
                this.tags = detail.genres?.mapNotNull { it.name }
                this.duration = detail.runtime
            }
        }

        // Série : fiche + toutes les saisons en parallèle
        val detail = AppUtils.parseJson<WwTvDetail>(
            app.get("$tmdbProxy/tv/$tmdb", headers = baseHeaders).text
        )
        val title = detail.name?.trim()?.takeIf { it.isNotBlank() } ?: "Série"
        val seasons = detail.seasons?.filter { (it.seasonNumber ?: 0) > 0 && (it.episodeCount ?: 0) > 0 }.orEmpty()
        // Le secours anime (AnimoFlix) n'est activé que pour les dessins animés/animes
        // (genre Animation) ou les très longues séries — évite les confusions de titres.
        val isAnimation = detail.genres.orEmpty().any {
            it.name.equals("Animation", true) || it.name.equals("Animération", true)
        }

        // (saison, épisode, sIndex = place dans la saison, absIndex = place absolue)
        data class RawEp(
            val season: Int, val number: Int, val sIndex: Int, val absIndex: Int,
            val name: String?, val still: String?, val overview: String?,
            val runtime: Int?, val score: Double?
        )

        val rawEpisodes = coroutineScope {
            seasons.map { season ->
                async(Dispatchers.IO) {
                    runCatching {
                        val n = season.seasonNumber!!
                        val json = app.get("$tmdbProxy/tv/$tmdb/season/$n", headers = baseHeaders).text
                        val eps = AppUtils.parseJson<WwSeasonDetail>(json).episodes.orEmpty()
                            .filter { (it.episodeNumber ?: 0) > 0 }
                            .sortedBy { it.episodeNumber }
                        val first = eps.firstOrNull()?.episodeNumber ?: 0
                        eps.mapIndexed { idx, ep ->
                            RawEp(
                                n, ep.episodeNumber!!,
                                (ep.episodeNumber!! - first + 1), (ep.episodeNumber!! - first + 1), // absIndex corrigé après
                                ep.name, ep.stillPath, ep.overview, ep.runtime, ep.voteAverage
                            )
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }.sortedWith(compareBy({ it.season }, { it.number }))

        // Index absolus : position cumulée sur toute la série (1er épisode = 1)
        var absBase = 0
        val perSeasonCounts = rawEpisodes.groupBy { it.season }
        val episodes = rawEpisodes.map { raw ->
            val absIndex = absBase + raw.absIndex
            val isLast = perSeasonCounts[raw.season]?.lastOrNull() === raw
            if (isLast) absBase += perSeasonCounts[raw.season]!!.size
            val afFlag = if (isAnimation || rawEpisodes.size >= 60) 1 else 0
            val packed = "?t=" + URLEncoder.encode(title, "UTF-8") +
                "&i=${raw.sIndex}&a=$absIndex&af=$afFlag"
            newEpisode("$mainUrl/tv/$tmdb/${raw.season}/${raw.number}$packed") {
                this.name = raw.name?.trim()?.takeIf { it.isNotBlank() && it != "Épisode ${raw.number}" }
                    ?: "Épisode ${raw.number}"
                this.season = raw.season
                this.episode = raw.number
                this.posterUrl = raw.still?.let { "$tmdbImage/w500$it" }
                this.description = raw.overview?.trim()?.takeIf { it.isNotBlank() }
                this.score = raw.score?.let { Score.from10(it) }
                this.runTime = raw.runtime
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = detail.posterPath?.let { "$tmdbImage/w500$it" }
            this.backgroundPosterUrl = detail.backdropPath?.let { "$tmdbImage/original$it" }
            this.year = detail.firstAirDate?.take(4)?.toIntOrNull()
            this.score = detail.voteAverage?.let { Score.from10(it) }
            this.plot = detail.overview?.trim()?.takeIf { it.isNotBlank() }
            this.tags = detail.genres?.mapNotNull { it.name }
        }
    }

    // -------------------------------------------------------------------------
    // Lecture — un maximum de serveurs en parallèle
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val m = Regex("""/tv/(\d+)/(\d+)/(\d+)(?:\?([^#]*))?|/movie/(\d+)""").find(data)
            ?: return false
        val tmdb: String
        var season: Int? = null
        var episode: Int? = null
        val params: Map<String, String>
        if (m.groupValues[1].isNotEmpty()) {
            tmdb = m.groupValues[1]
            season = m.groupValues[2].toIntOrNull()
            episode = m.groupValues[3].toIntOrNull()
            params = parseParams(m.groupValues[4])
        } else {
            tmdb = m.groupValues[5]
            params = emptyMap()
        }
        val isTv = season != null
        val showTitle = params["t"]
        val seasonIndex = params["i"]?.toIntOrNull()
        val absIndex = params["a"]?.toIntOrNull()
        val animeAllowed = params["af"] == "1"

        var found = false
        val lock = Any()
        val doctorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val doctorJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
        val seenUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        /** N'émet un lien qu'une fois vérifié jouable (anti-erreurs 3003). */
        fun emit(link: ExtractorLink) {
            synchronized(seenUrls) { if (seenUrls.contains(link.url)) return }
            seenUrls.add(link.url)
            doctorJobs += doctorScope.launch {
                if (isPlayableBlocking(link)) {
                    synchronized(lock) {
                        found = true
                        callback(link)
                    }
                }
            }
        }

        val hostLinks = LinkedHashMap<String, HostLink>()
        suspend fun addHostLinks(links: List<HostLink>) {
            synchronized(hostLinks) { links.forEach { if (it.url.startsWith("http")) hostLinks.putIfAbsent(it.url, it) } }
        }

        coroutineScope {
            val aggregators = mutableListOf<kotlinx.coroutines.Deferred<List<HostLink>>>()
            aggregators += async(Dispatchers.IO) { runCatching { apiwiflixLinks(tmdb, season, episode) }.getOrDefault(emptyList()) }
            // playerix : en TV seuls les boutons HLS (data-fmt="m3u8") sont fiables —
            // les boutons iframe renvoient les mêmes liens pour tous les épisodes (vérifié).
            aggregators += async(Dispatchers.IO) { runCatching { playerixLinks(tmdb, season, episode, m3u8Only = isTv) }.getOrDefault(emptyList()) }
            aggregators += async(Dispatchers.IO) { runCatching { zeusLinks(tmdb, season, episode) }.getOrDefault(emptyList()) }
            aggregators += async(Dispatchers.IO) { runCatching { movixLinks(tmdb, season, episode) }.getOrDefault(emptyList()) }
            aggregators += async(Dispatchers.IO) { runCatching { movixFstreamLinks(tmdb, season) }.getOrDefault(emptyList()) }
            // mouve : films uniquement — en TV la majorité des liens ne dépendent pas
            // de l'épisode demandé (vérifié : mêmes URLs pour 2x1 et 2x2).
            if (!isTv) {
                aggregators += async(Dispatchers.IO) { runCatching { mouveLinks(tmdb, null, null) }.getOrDefault(emptyList()) }
            }

            // ---- 1embed : playlists HLS directes (fiable) ----
            async(Dispatchers.IO) {
                runCatching {
                    val oneEmbedUrl = if (isTv) {
                        "https://1embed.cc/embed/tv/$tmdb/$season/$episode"
                    } else {
                        "https://1embed.cc/embed/movie/$tmdb"
                    }
                    OneEmbed().getUrl(oneEmbedUrl, mainUrl, subtitleCallback) { emit(it) }
                }
            }

            // ---- AnimoFlix : épisodes d'animes (One Piece & co), y compris les tout derniers ----
            if (isTv && animeAllowed && showTitle != null && seasonIndex != null && absIndex != null) {
                aggregators += async(Dispatchers.IO) {
                    runCatching { animoflixLinks(showTitle, season!!, seasonIndex, absIndex) }.getOrDefault(emptyList())
                }
            }

            aggregators.awaitAll().forEach { addHostLinks(it) }

            // ---- Lecteurs de secours du site + lecteurs publics ----
            addHostLinks(fallbackEmbeds(tmdb, season, episode))
        }

        // ---- Tri : VOSTFR d'abord (site VOSTFR), puis VO, puis VF ----
        fun priority(hl: HostLink): Int {
            val langP = when (hl.lang) {
                "VOSTFR" -> 0
                "VO" -> 1
                "VF", "VFQ", "VFF", "TRUEFRENCH", "FR", "VF/VO" -> 2
                "MULTI" -> 3
                null -> 4
                else -> 5
            }
            val originP = when (hl.origin) {
                "zeus" -> 0; "animoflix" -> 1; "apiwiflix" -> 2; "playerix" -> 3; "movix" -> 4
                "fstream" -> 5; "site" -> 6; "embed" -> 7; "animoflix-r" -> 8; else -> 9
            }
            return langP * 10 + originP
        }

        val ordered = synchronized(hostLinks) { hostLinks.values.toList() }
            .sortedWith(compareBy(::priority))

        // ---- Playlists HLS directes : émises immédiatement ----
        ordered.filter { it.direct }.forEach { hl ->
            val name = "Afterdark · ${hl.label}" + (hl.lang?.let { " · $it" } ?: "")
            emit(
                newExtractorLink(name, name, hl.url) {
                    this.quality = hl.quality
                    this.type = ExtractorLinkType.M3U8
                }
            )
        }

        // ---- Extraction parallèle sur le reste ----
        val toExtract = ordered.filter { !it.direct }
        if (toExtract.isNotEmpty()) {
            val semaphore = Semaphore(6)
            coroutineScope {
                toExtract.map { hl ->
                    async(Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            var produced = 0
                            if ("vidara.to" in hl.url) {
                                // L'extracteur intégré marque tout en HLS → erreurs 3003 ;
                                // le nôtre vérifie le vrai type de fichier
                                runCatching {
                                    Vidara().getUrl(hl.url, mainUrl, { sub ->
                                        synchronized(lock) { subtitleCallback(sub) }
                                    }) { link ->
                                        produced++
                                        emit(relabel(link, hl.lang))
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
                            if (produced == 0) {
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

        doctorJobs.forEach { it.join() }
        doctorScope.cancel()
        return found
    }

    /** Paramètres de l'URL de données (?t=…&i=…&a=…&af=…). */
    private fun parseParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return Regex("""(?:^|&)([a-z]+)=([^&]*)""")
            .findAll(query)
            .associate {
                it.groupValues[1] to runCatching {
                    URLDecoder.decode(it.groupValues[2], "UTF-8")
                }.getOrDefault(it.groupValues[2])
            }
    }

    // -------------------------------------------------------------------------
    // Docteur de liens : chaque lien est VÉRIFIÉ jouable avant d'être affiché
    // (petite requête Range) — élimine les erreurs ExoPlayer 3003 « container
    // unsupported » des liens morts, des pages HTML et des fichiers mal typés.
    // En cas de doute (timeout, réseau), le lien est conservé.
    // -------------------------------------------------------------------------
    private val doctorClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun isPlayableBlocking(link: ExtractorLink): Boolean = try {
        val builder = okhttp3.Request.Builder().url(link.url).header("Range", "bytes=0-1023")
        val headers = (link.headers ?: emptyMap()).toMutableMap()
        if (!headers.containsKey("User-Agent")) headers["User-Agent"] = USER_AGENT
        if (link.referer.isNotBlank() && !headers.containsKey("Referer")) headers["Referer"] = link.referer
        headers.forEach { (k, v) -> builder.header(k, v) }
        doctorClient.newCall(builder.build()).execute().use { res ->
            if (!res.isSuccessful) return@use false
            val body = res.body ?: return@use false
            val head = ByteArray(512)
            var n = 0
            while (n < 512) {
                val read = body.byteStream().read(head, n, 512 - n)
                if (read <= 0) break
                n += read
            }
            if (n <= 0) return@use false
            val contentType = (res.header("Content-Type") ?: "").lowercase()
            val prefix = String(head, 0, n, Charsets.ISO_8859_1)
            val isFtyp = n >= 12 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
            val isEbml = n >= 4 && (head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
                (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3
            val isTs = (head[0].toInt() and 0xFF) == 0x47
            when {
                link.type == ExtractorLinkType.DASH || ".mpd" in link.url ->
                    prefix.contains("<MPD") || prefix.contains("<?xml")
                link.type == ExtractorLinkType.M3U8 || ".m3u8" in link.url || contentType.contains("mpegurl") ->
                    prefix.contains("#EXTM3U") || contentType.contains("mpegurl")
                contentType.startsWith("video/") || contentType.startsWith("audio/") -> true
                prefix.contains("#EXTM3U") -> true
                isFtyp || isEbml || isTs || prefix.startsWith("RIFF") -> true
                else -> false
            }
        }
    } catch (e: Exception) {
        true // doute réseau → on garde le lien
    }

    /** Ré-étiquette un lien avec sa langue (« Filemoon · VOSTFR ») pour l'utilisateur. */
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
    // Secours anime — épisodes d'animoflix.to (One Piece, Naruto, démons & co).
    // animoflix a les DERNIERS épisodes VOSTFR là où les agrégateurs culbutent,
    // avec un hébergeur maison (ansembed, HLS) + sibnet.
    // -------------------------------------------------------------------------
    private val animoflixCf by lazy { CloudflareKiller() }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AfSuggestion(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null
    )

    /** Titre normalisé (minuscules, sans accents ni ponctuation) pour comparer. */
    private fun normalizeTitle(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]"), "")

    /** Trouve l'anime sur animoflix.to par son titre (autocomplétion du site). */
    private suspend fun animoflixSlug(title: String): String? {
        val json = runCatching {
            app.get(
                "https://animoflix.to/search-autocomplete.php",
                params = mapOf("q" to title),
                interceptor = animoflixCf
            ).text
        }.getOrNull() ?: return null
        val suggestions = AppUtils.tryParseJson<Array<AfSuggestion>>(json) ?: return null
        val wanted = normalizeTitle(title)
        return suggestions.firstOrNull { normalizeTitle(it.title ?: "") == wanted }?.slug
            ?: suggestions.firstOrNull {
                it.title != null && suggestions.size == 1 &&
                    (wanted.contains(normalizeTitle(it.title!!)) || normalizeTitle(it.title!!).contains(wanted))
            }?.slug
    }

    /**
     * Épisode d'un anime sur animoflix.to. Le site numérote PAR SAISON (saison-N),
     * essentiellement le même découpage que TMDB — on essaie aussi les variantes
     * « sans saison / numéro absolu » et « saison 1 / numéro absolu » utilisées
     * par d'autres animes. Chaque page est VÉRIFIÉE (le titre doit contenir
     * « Épisode {n} ») car une page inexistante redirige en douce vers l'épisode 1.
     */
    private suspend fun animoflixLinks(
        title: String,
        season: Int,
        seasonIndex: Int,
        absIndex: Int
    ): List<HostLink> {
        val slug = animoflixSlug(title) ?: return emptyList()
        val out = mutableListOf<HostLink>()

        val variants = mutableListOf<Pair<String, String>>()
        if (!outHasLang(out, "VOSTFR")) variants += "saison-$season/vostfr/episode-$seasonIndex" to "VOSTFR"
        variants += listOf(
            "vostfr/episode-$absIndex" to "VOSTFR",
            "saison-1/vostfr/episode-$absIndex" to "VOSTFR",
            "saison-$season/vf/episode-$seasonIndex" to "VF",
            "vf/episode-$absIndex" to "VF",
            "saison-1/vf/episode-$absIndex" to "VF"
        )

        for ((path, lang) in variants) {
            if (outHasLang(out, lang)) continue
            val url = "https://animoflix.to/anime/$slug/$path"
            val html = runCatching {
                app.get(url, interceptor = animoflixCf).text
            }.getOrNull() ?: continue

            // Vérification : la page doit bien être celle de l'épisode demandé
            val requested = Regex("""episode-(\d+)$""").find(path)?.groupValues?.get(1) ?: continue
            val pageTitle = Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1) ?: continue
            if (!Regex("""\b[pé]+isode\s*$requested(\D|$)""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(java.text.Normalizer.normalize(pageTitle, java.text.Normalizer.Form.NFD))
            ) continue

            val select = Regex(
                """<select[^>]*id="epLecteurSelect"[^>]*>(.*?)</select>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)
            val options = select?.let {
                Regex("""<option([^>]*)>([^<]*)</option>""").findAll(it.groupValues[1]).toList()
            } ?: emptyList()
            for (opt in options) {
                val value = Regex("""value="([^"]+)"""").find(opt.groupValues[1])?.groupValues?.get(1) ?: continue
                if (!value.startsWith("http")) continue
                val restricted = """data-restricted="true"""" in opt.groupValues[1]
                val label = opt.groupValues[2].trim().ifBlank { "Lecteur" }
                out += HostLink(
                    value, lang, "AnimoFlix $label",
                    if (restricted) "animoflix-r" else "animoflix"
                )
            }
        }
        return out
    }

    private fun outHasLang(list: List<HostLink>, lang: String) = list.any { it.lang == lang }

    // -------------------------------------------------------------------------
    // Lecteurs de secours publics — les 3 du site (URL tirées de son bundle)
    // + d'autres lecteurs publics compatibles TMDB (tous testés vivants)
    // -------------------------------------------------------------------------
    private fun fallbackEmbeds(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val out = mutableListOf<HostLink>()

        // --- Les lecteurs de secours du site Afterdark lui-même ---
        out += if (season != null) {
            HostLink(
                "https://player.videasy.net/tv/$tmdb/$season/$episode?overlay=true&color=8B5CF6&nextEpisode=true&episodeSelector=true",
                null, "Videasy", "site"
            )
        } else {
            HostLink("https://player.videasy.net/movie/$tmdb?overlay=true&color=8B5CF6", null, "Videasy", "site")
        }
        out += if (season != null) {
            HostLink("https://frembed.skin/embed/serie/$tmdb?sa=$season&epi=$episode", null, "Frembed", "site")
        } else {
            HostLink("https://frembed.skin/embed/movie/$tmdb", null, "Frembed", "site")
        }
        out += if (season != null) {
            HostLink(
                "https://peachify.top/embed/tv/$tmdb/$season/$episode?dub=French&sub=French&autoNext=30",
                null, "Peachify", "site"
            )
        } else {
            HostLink("https://peachify.top/embed/movie/$tmdb?dub=French&sub=French", null, "Peachify", "site")
        }

        // --- Autres lecteurs publics TMDB (multi-serveurs, best-effort) ---
        val publicEmbeds = if (season != null) {
            listOf(
                "https://vidfast.pro/tv/$tmdb/$season/$episode?autoPlay=true&sub=fr" to "VidFast",
                "https://vidsrc.cc/v2/embed/tv/$tmdb/$season/$episode" to "VidSrc.cc",
                "https://www.vidsrc.wtf/api/2/tv/?id=$tmdb&s=$season&e=$episode" to "VidSrc.wtf",
                "https://www.2embed.cc/embedtv/$tmdb&s=$season&e=$episode" to "2Embed",
                "https://111movies.com/tv/$tmdb/$season/$episode" to "111Movies",
                "https://www.braflix.win/watch/$tmdb?s=$season&e=$episode" to "Braflix",
                "https://www.vidking.net/embed/tv/$tmdb/$season/$episode?autoPlay=true" to "VidKing"
            )
        } else {
            listOf(
                "https://vidfast.pro/movie/$tmdb?autoPlay=true&sub=fr" to "VidFast",
                "https://vidsrc.cc/v2/embed/movie/$tmdb" to "VidSrc.cc",
                "https://www.vidsrc.wtf/api/3/movie/?id=$tmdb" to "VidSrc.wtf",
                "https://www.2embed.cc/embed/$tmdb" to "2Embed",
                "https://111movies.com/movie/$tmdb" to "111Movies",
                "https://www.braflix.win/watch/$tmdb" to "Braflix",
                "https://www.vidking.net/embed/movie/$tmdb?autoPlay=true" to "VidKing"
            )
        }
        publicEmbeds.forEach { (u, label) -> out += HostLink(u, null, label, "embed") }

        return out
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
            HostLink(it.url!!, normalizeLang(it.language), it.name ?: "Lecteur", "apiwiflix")
        }
    }

    /** apis.wavewatch.top/playerix.php — boutons data-url avec langue + HLS directs.
     *  En TV (m3u8Only=true), seuls les boutons HLS sont retenus : les iframes ne
     *  dépendent pas de l'épisode demandé (mêmes URLs pour tous les épisodes, vérifié). */
    private suspend fun playerixLinks(
        tmdb: String,
        season: Int?,
        episode: Int?,
        m3u8Only: Boolean = false
    ): List<HostLink> {
        val url = if (season != null && episode != null) {
            "https://apis.wavewatch.top/playerix.php?type=tv&id=$tmdb&season=$season&episode=$episode"
        } else {
            "https://apis.wavewatch.top/playerix.php?type=movie&id=$tmdb"
        }
        val html = app.get(url, headers = baseHeaders).text
        val out = mutableListOf<HostLink>()
        val seenHosts = mutableSetOf<String>()
        Regex("""<button([^>]*data-url[^>]*)>(.*?)</button>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val attrs = m.groupValues[1].replace("&amp;", "&")
                val content = m.groupValues[2]
                val dataUrl = Regex("""data-url="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
                val fmt = Regex("""data-fmt="([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: "iframe"
                if (Regex("""data-alive="0"""").containsMatchIn(attrs)) return@forEach
                if (m3u8Only && fmt != "m3u8") return@forEach
                val lang = Regex("""class="lang"[^>]*>\s*([^<]+)""").find(content)?.groupValues?.get(1)
                    ?.trim()?.split(" ")?.lastOrNull() // « 🇫🇷 VF » -> VF
                    ?.takeIf { it.isNotBlank() && it != "?" }
                val b64 = Regex("""u=([A-Za-z0-9+/=_-]{16,})""").find(dataUrl)?.groupValues?.get(1) ?: return@forEach
                val decoded = decodeB64(b64) ?: return@forEach
                if (!decoded.startsWith("http")) return@forEach
                val host = hostOf(decoded)

                if (fmt == "m3u8") {
                    out += HostLink(decoded, lang ?: "MULTI", host, "playerix", direct = true)
                } else {
                    val key = "$host|${lang ?: "?"}"
                    if (key in seenHosts) return@forEach
                    seenHosts += key
                    out += HostLink(decoded, lang, host, "playerix")
                }
            }
        return out.take(40)
    }

    /** zeus.php en Server-Sent Events — langues réelles (VF/VFQ/VOSTFR/MULTI) + HLS direct. */
    private suspend fun zeusLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = buildString {
            append("https://apis.wavewatch.top/zeus.php?sse&type=")
            append(if (season != null) "tv" else "movie")
            append("&id=").append(tmdb)
            append("&s=").append(season ?: 1).append("&e=").append(episode ?: 1)
        }
        val body = app.get(url, headers = baseHeaders).text
        val out = mutableListOf<HostLink>()
        Regex("""data:\s*(\{.*?\})\s*\n""", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
            val parsed = AppUtils.tryParseJson<WwSseEvent>(m.groupValues[1]) ?: return@forEach
            parsed.sources?.forEach { s ->
                val u = s.url ?: return@forEach
                if (!u.startsWith("http") || s.premium == true) return@forEach
                val lang = normalizeLang(s.lang)
                val label = hostOf(u)
                val format = s.format?.lowercase()
                if ((format == "hls" || format == "mp4" || format == "dash") && s.iframe != true) {
                    out += HostLink(u, lang, label, "zeus", direct = true, quality = qualityValue(s.quality))
                    return@forEach
                }
                out += HostLink(u, lang, label, "zeus", quality = qualityValue(s.quality))
            }
        }
        return out
    }

    /** mouve.php?json=1 — grand agrégateur (films uniquement : en TV les liens
     *  ne dépendent pas de l'épisode, vérifié). */
    private suspend fun mouveLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = buildString {
            append("https://apis.wavewatch.top/mouve.php?json=1&type=")
            append(if (season != null) "tv" else "movie")
            append("&id=").append(tmdb)
            append("&s=").append(season ?: 1).append("&e=").append(episode ?: 1)
        }
        val root = AppUtils.parseJson<WwMouveResponse>(app.get(url, headers = baseHeaders).text)
        val out = mutableListOf<HostLink>()
        val seenHosts = mutableSetOf<String>()
        root.streams?.forEach { s ->
            val u = s.url ?: return@forEach
            val format = s.format?.lowercase()
            if (format == "hls") {
                val real = Regex("""[?&]url=([^&]+)""").find(u)?.groupValues?.get(1)
                    ?.let { decodeUrlParam(it) } ?: u
                if (real.startsWith("http") && real.contains(".m3u8")) {
                    out += HostLink(real, null, hostOf(real), "mouve", direct = true)
                }
                return@forEach
            }
            if (!u.startsWith("http")) return@forEach
            val host = hostOf(u)
            if (host in seenHosts) return@forEach
            seenHosts += host
            out += HostLink(u, null, host, "mouve")
        }
        return out
    }

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
    private suspend fun movixFstreamLinks(tmdb: String, season: Int?): List<HostLink> {
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
                val name = "Afterdark · $label" + (lang?.let { " · $it" } ?: "")
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
    // Petits utilitaires
    // -------------------------------------------------------------------------
    /** Codes de langue du site : vf | vff | vfq | vo | vostfr (+ agrégateurs). */
    private fun normalizeLang(lang: String?): String? {
        val l = lang?.trim()?.uppercase() ?: return null
        return when {
            l in setOf("VF", "FR", "FRENCH") -> "VF"
            l in setOf("VFQ", "VFF", "TRUEFRENCH") -> if (l == "TRUEFRENCH") "VFQ" else l
            l == "VOSTFR" -> "VOSTFR"
            l == "VO" -> "VO"
            l == "MULTI" -> "MULTI"
            l.isEmpty() -> null
            else -> l.take(8)
        }
    }

    private fun hostOf(url: String): String =
        Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: "Lecteur"

    private fun qualityValue(q: String?): Int = when {
        q == null -> Qualities.Unknown.value
        q.contains("2160") || q.contains("4K", ignoreCase = true) -> Qualities.P2160.value
        q.contains("1080") -> Qualities.P1080.value
        q.contains("720") -> Qualities.P720.value
        q.contains("480") -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun decodeB64(s: String): String? = runCatching {
        String(Base64.decode(s, Base64.URL_SAFE))
    }.getOrNull() ?: runCatching {
        String(Base64.decode(s, Base64.DEFAULT))
    }.getOrNull()

    private fun decodeUrlParam(s: String): String? = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrNull()

    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")

    /** Filtre les faux positifs (images, pubs, YouTube, sous-titres…) qui causent
     *  des erreurs de lecture du type « container unsupported ». */
    private val junkFilterRegex = Regex(
        """(?i)(youtube|youtu\.be|dailymotion|\.jpg|\.jpeg|\.png|\.gif|\.webp|\.svg|\.vtt|\.srt|""" +
            """/ads?/|adserve|adservice|adsystem|doubleclick|banner|/pixel|analytics|/thumb|poster|trailer)"""
    )
}

// -------------------------------------------------------------------------
// Modèles JSON
// -------------------------------------------------------------------------

// Proxy TMDB : trending|discover|search|upcoming
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwTmdbPage(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("results") val results: List<WwTmdbItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwTmdbItem(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null
)

// fiche film
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwMovieDetail(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genres") val genres: List<WwGenre>? = null
)

// fiche série
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwTvDetail(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: List<WwGenre>? = null,
    @JsonProperty("seasons") val seasons: List<WwSeason>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwSeason(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null,
    @JsonProperty("name") val name: String? = null
)

// saison détaillée
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwSeasonDetail(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episodes") val episodes: List<WwEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null
)

// zeus.php (SSE)
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwSseEvent(
    @JsonProperty("sources") val sources: List<WwSseSource>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwSseSource(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("iframe") val iframe: Boolean? = null,
    @JsonProperty("premium") val premium: Boolean? = null
)

// mouve.php?json=1
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwMouveResponse(
    @JsonProperty("streams") val streams: List<WwMouveStream>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwMouveStream(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("iframe") val iframe: Boolean? = null
)

// apiwiflix
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiWiflixSource(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("language") val language: String? = null
)

// api.movix.cash
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
