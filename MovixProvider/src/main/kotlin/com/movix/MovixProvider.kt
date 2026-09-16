package com.movix

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
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
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
// Movix — films & séries VF/VOSTFR (vidzy, kakaflix/dood, voe…)
// ===========================================================================
// Site : https://movix.zip (Laravel + Livewire + Alpine, images TMDB).
//   Listes    : /movies?page=N · /tv-shows?page=N · /trending · /top-imdb
//               cartes : <a href=/movie/{slug}> + <img data-src alt=TITRE>
//   Recherche : POST /livewire/update du composant search-component avec
//               updates={q:…} (le snapshot est présent sur chaque page)
//   Film      : /movie/{slug} → versionController() : videos =
//               [{server_name,label,version(TRUEFRENCH/VF/VOSTFR),link}]
//   Série     : /tv-show/{slug} → boutons de saisons (wire:click
//               updateSeason('id')) + épisodes de la saison par défaut ;
//               les autres saisons via POST /livewire/update du
//               season-component ; épisode : /episode/{slug}/{s}-{e}
//   Extraction: embeds vidzy.cc/.live (sources videojs XOR base64(hostname)),
//               kakaflix.lol (dood/voe) → fallback générique (regex sources
//               + p.a.c.k.e.r + XOR).
//   v3 : les serveurs propres au site sont parfois murés (vidzy.org = page
//   d'attente 180 s, uqload.net = 403, multiup = hébergeurs de télécharge-
//   ment, flixeo = instable) → SUPPLÉMENT agrégateur vidsrc.buzz : titre →
//   id IMDb (API suggestion IMDb, sans clé) → /embed/{type}/{imdb}[/s/e]
//   → var Q → /pl/api.php?a=sources → a=play → HLS proxysés multi-serveurs.
// ===========================================================================
@CloudstreamPlugin
class MovixPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        MovixProvider.appContext = context.applicationContext
        registerMainAPI(MovixProvider())
        openSettings = { ctx -> MovixProvider.showSettings(ctx) }
    }
}

class MovixProvider : MainAPI() {
    companion object {
        const val DEFAULT_URL = "https://movix.zip"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "movix_settings"
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
                setText(currentUrl()); hint = "https://movix.zip"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de Movix")
                .setMessage("Si le site change de domaine, indiquez l'adresse actuelle.")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setSiteUrl(context, DEFAULT_URL) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "Movix"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun headers() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    private val cfKiller by lazy { com.lagradost.cloudstream3.network.CloudflareKiller() }

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
        syncUrl()
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
        syncUrl()
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
        syncUrl()
        val html = runCatching { app.get(url, headers = headers(), interceptor = cfKiller).text }.getOrNull()
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")
        val slug = url.trimEnd('/').substringAfterLast('/')
        val isSeries = "/tv-show/" in url

        val title = Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1)
            ?.substringBefore(" streaming")?.trim()?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
        val poster = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta name="description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        val year = Regex("""(\d{4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val score = Regex("""([\d.]+)\s*(?:/|sur)\s*10""").find(html)?.groupValues?.get(1)?.toDoubleOrNull()

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
                    episodes += newEpisode("$mainUrl/episode/${m.groupValues[1]}/$s-$e") {
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

    // -------------------------------------------------------------------------
    // Supplément agrégateur : IMDb suggestion (sans clé) → vidsrc.buzz
    // (chaîne validée : embed → var Q → a=sources → a=play → HLS proxy)
    // -------------------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SuggestionEntry(val id: String? = null, val l: String? = null, val qid: String? = null, val y: Int? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsQ(val type: String? = null, val id: String? = null, val s: Int? = null, val e: Int? = null, val t: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsServer(val ref: String? = null, val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsPlay(val url: String? = null)

    /** Titre (slug) → id IMDb via l'API de suggestion publique (sans clé). */
    private suspend fun imdbIdFor(query: String, isTv: Boolean): String? = runCatching {
        val q = query.trim().replace(Regex("""\s+"""), " ")
        if (q.isEmpty()) return null
        // l'API de suggestion IMDb préfère une requête SANS ponctuation
        // (« Fifty / Fifty » échoue, « fifty fifty » matche)
        val plain = q.replace(Regex("""[^\p{L}\p{N}\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (plain.isEmpty()) return null
        val first = plain.first().lowercaseChar()
        if (!first.isLetterOrDigit()) return null
        val url = "https://v2.sg.media-imdb.com/suggestion/$first/" +
            "${java.net.URLEncoder.encode(plain, "UTF-8").replace("+", "%20")}.json"
        val body = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")).text
        val arr = Regex(""""d"\s*:\s*(\[.*\])\s*,\s*"q"""").find(body)?.groupValues?.get(1) ?: return null
        val entries = AppUtils.parseJson<List<SuggestionEntry>>(arr)
        val ttEntries = entries.filter { it.id?.startsWith("tt") == true }
        if (ttEntries.isEmpty()) return null
        val wanted = if (isTv) setOf("tvSeries", "tvMiniSeries") else setOf("movie")
        // ⚠ correspondance stricte du titre : l'API renvoie des titres « proches »
        // (ex. « The Final Game of Death » → « The Death of Robin Hood ») ;
        // ne jamais servir l'agrégateur sur un titre différent.
        (ttEntries.firstOrNull { it.qid in wanted && titlesMatch(it.l ?: "", plain) })
            ?: ttEntries.firstOrNull { titlesMatch(it.l ?: "", plain) }
    }.getOrNull()?.id

    /** Titres « équivalents » après normalisation (accents, ponctuation, casse). */
    private fun titlesMatch(a: String, b: String): Boolean {
        fun norm(x: String) = x.lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val (na, nb) = norm(a) to norm(b)
        if (na.length < 2 || nb.length < 2) return false
        return na == nb || na.contains(nb) || nb.contains(na)
    }

    /** vidsrc.buzz — HLS proxysés multi-serveurs (films, séries, animes). */
    private suspend fun vidsrcBuzzLinks(
        imdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = if (isTv && season != null && episode != null) {
            "https://vidsrc.buzz/embed/tv/$imdbId/$season/$episode"
        } else if (isTv) {
            "https://vidsrc.buzz/embed/tv/$imdbId/1/1"
        } else {
            "https://vidsrc.buzz/embed/movie/$imdbId"
        }
        val html = runCatching { app.get(embedUrl, headers = mapOf("User-Agent" to USER_AGENT)).text }.getOrNull()
            ?: return false
        val qm = Regex("""var Q = (\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return false
        val q = runCatching { AppUtils.parseJson<VsQ>(qm.groupValues[1]) }.getOrNull() ?: return false
        val id = q.id?.takeIf { it.isNotBlank() } ?: return false
        val token = q.t?.takeIf { it.isNotBlank() } ?: return false
        val qs = "type=${q.type ?: "movie"}&id=${java.net.URLEncoder.encode(id, "UTF-8")}" +
            "&s=${q.s ?: 0}&e=${q.e ?: 0}&t=${java.net.URLEncoder.encode(token, "UTF-8")}"
        val srcJson = runCatching {
            app.get(
                "https://vidsrc.buzz/pl/api.php?a=sources&$qs",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Accept" to "application/json"
                )
            ).text
        }.getOrNull() ?: return false
        val servers = runCatching { AppUtils.parseJson<List<VsServer>>(srcJson) }.getOrNull() ?: return false
        // serveurs interrogés EN PARALLÈLE (certains renvoient 502 : la liste
        // ne doit pas attendre les timeouts des serveurs morts)
        val foundFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        coroutineScope {
            servers.take(4).forEach { sv ->
                launch(Dispatchers.IO) {
                    val ref = sv.ref?.takeIf { it.isNotBlank() } ?: return@launch
                    val svName = sv.name?.replace(Regex("""^Server\s+"""), "")?.trim().takeIf { !it.isNullOrBlank() } ?: "Agrégateur"
                    var u: String? = null
                    for (attempt in 1..2) {
                        val play = runCatching {
                            app.get(
                                "https://vidsrc.buzz/pl/api.php?a=play&ref=${java.net.URLEncoder.encode(ref, "UTF-8")}" +
                                    "&t=${java.net.URLEncoder.encode(token, "UTF-8")}",
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to embedUrl,
                                    "Accept" to "application/json"
                                )
                            ).text
                        }.getOrNull() ?: break
                        u = runCatching { AppUtils.parseJson<VsPlay>(play).url }.getOrNull()
                        if (!u.isNullOrBlank()) break
                        if (attempt == 1) delay(1200)
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

        // slug + type + épisode + titre de la page, pour l'agrégateur vidsrc.buzz
        val path = data.substringAfter("://", "").substringAfter('/', "").trim('/')
        val parts = path.split("/")
        val isTvEpisode = parts.firstOrNull() == "episode"
        val isTvShow = parts.firstOrNull() == "tv-show" || isTvEpisode
        val slug = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val se = parts.getOrNull(2)?.split("-")
        val season = se?.getOrNull(0)?.toIntOrNull()
        val episode = se?.getOrNull(1)?.toIntOrNull()
        // titre réel de la fiche (plus fiable que le slug pour IMDb) :
        // « Game of Thrones streaming vf… » / « … Complet VF/VOSTFR »
        val rawTitle = Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val pageTitle = rawTitle
            .substringBefore(" streaming").substringBefore(" Complet")
            .substringBefore(" VF/").substringBefore(", ").trim()
            .takeIf { it.length >= 2 }
        suspend fun tryAggregator(): Boolean {
            val query = pageTitle ?: slug?.replace('-', ' ') ?: return false
            val imdb = imdbIdFor(query, isTvShow) ?: return false
            return runCatching { vidsrcBuzzLinks(imdb, isTvShow, season, episode, callback) }.getOrDefault(false)
        }

        val foundFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val videosJson = Regex("""(?:const|var|let)\s+videos\s*=\s*(\[[\s\S]*?\]);""").find(html)?.groupValues?.get(1)
        val videos = videosJson?.let { runCatching { AppUtils.parseJson<List<MvVideo>>(it) }.getOrNull() }

        // fallback : iframes d'embed visibles dans le HTML (en parallèle, bornées)
        if (videos.isNullOrEmpty()) {
            coroutineScope {
                Regex("""<iframe[^>]*\ssrc="(https?://[^"]+)"""").findAll(html).forEach { m ->
                    val u = m.groupValues[1]
                    if (u.contains("movix.zip") || u.contains("google") || u.contains("facebook")) return@forEach
                    launch(Dispatchers.IO) {
                        runCatching {
                            withTimeoutOrNull(15_000) {
                                if (loadExtractor(u, data, subtitleCallback) { l ->
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

        // serveurs du site — EN PARALLÈLE, chacun borné à 15 s
        // (vidzy.org = attente 180 s, flixeo = timeouts : ne jamais bloquer
        // toute la liste sur un hôte mort)
        coroutineScope {
            videos.forEach { v ->
                launch(Dispatchers.IO) {
                    val link = v.link?.takeIf { it.startsWith("http") } ?: return@launch
                    val version = v.version?.takeIf { it.isNotBlank() } ?: ""
                    val server = v.server_name?.takeIf { it.isNotBlank() } ?: name
                    val label = buildString {
                        append(server)
                        if (version.isNotBlank()) append(" · $version")
                        if (!v.label.isNullOrBlank()) append(" · ${v.label}")
                    }
                    runCatching {
                        withTimeoutOrNull(15_000) {
                            // extracteurs connus (dood, voe, filemoon, uqload, multiup…)
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

        // ---- Supplément agrégateur vidsrc.buzz (HLS multi-serveurs) ----
        val aggFound = tryAggregator()
        return foundFlag.get() || aggFound
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
