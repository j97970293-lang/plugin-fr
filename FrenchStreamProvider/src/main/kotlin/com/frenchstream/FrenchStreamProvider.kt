package com.frenchstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import org.jsoup.Jsoup

/**
 * Entry point of the plugin, this is the class that CloudStream loads.
 */
@CloudstreamPlugin
class FrenchStreamPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        FrenchStreamProvider.appContext = context.applicationContext
        registerMainAPI(FrenchStreamProvider())
        // vidara (filmoon) — évite l'extracteur intégré qui marque tout en HLS (erreurs 3003)
        registerExtractorAPI(Vidara())
        openSettings = { ctx -> FrenchStreamProvider.showSettings(ctx) }
    }
}

/**
 * vidara.to / vidaraa.cc… — hébergeur type Vidmoly (API /api/source/{id}).
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
        // Les 16 miroirs (vidaraw.com, vidaraa.cc…) partagent la même API et les
        // mêmes filecodes : on interroge le domaine du lien lui-même.
        val base = Regex("^(https?://[^/]+)").find(url)?.groupValues?.get(1) ?: mainUrl
        val fileCode = url.substringAfterLast("/").substringBefore("?")
        val res = runCatching {
            app.post(
                "$base/api/stream",
                json = mapOf("filecode" to fileCode, "device" to "web"),
                referer = base
            ).text
        }.getOrNull() ?: return
        val stream = Regex("\"streaming_url\"\\s*:\\s*\"([^\"]+)\"")
            .find(res)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
        if (!stream.startsWith("http")) return
        callback(
            newExtractorLink(name, name, stream) {
                this.type = if (".m3u8" in stream) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                this.referer = base
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

    companion object {
        /** Famille « StreamUp » : même API /api/stream et mêmes filecodes partout (vérifié). */
        private val domains = listOf(
            "vidara.to", "vidaraa.cc", "vidaraw.com", "vidarax.cc", "vidara.so",
            "vidavaca.net", "vidaarax.net", "vidaarax.com", "vidaratem.com",
            "odysseusa.cc", "handfacesnap.cc", "namefacesnap.cc", "thebesthosterv.com",
            "vidmatrixa.com", "vidchampions.com", "antarcticadocs.com", "nameitweb.com"
        )

        fun isVidara(url: String): Boolean {
            val host = Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1)?.lowercase() ?: return false
            return host in domains
        }
    }
}


// ===========================================================================
// French Stream (fs27.lol — URL éphémère annoncée par fstream.info)
//   · DLE : listes /films/, /series/ (+ /page/N/), recherche do=search OK.
//   · Fiches : /index.php?newsid=X (posters TMDB, tagz = ID TMDB f-XXX/s-XXX).
//   · FILM : /engine/ajax/film_api.php?id=X → { players: { nom: { vostfr, vff,
//     vfq, default: url } }, meta: { affiche, affiche2, trailer } }.
//   · SÉRIE : /static/series/{id}.js → { vf: { "1": { vidzy, uqload, netu,
//     voe, premium } }, vostfr: {...}, vo: {...}, info: {...} }.
//   · Hébergeurs : fsvid.lol (XOR videojs), vidzy.cc (XOR), vidaraa.cc
//     (Vidara), uqload.vc, kokoflix.lol (wrappers dood/voe/netu).
// ===========================================================================
class FrenchStreamProvider : MainAPI() {

    override var mainUrl = DEFAULT_URL
    override var name = "FrenchStream"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper by lazy { ObjectMapper() }

    // -------------------------------------------------------------------------
    // Réglages : le domaine change régulièrement (french-stream.one, fs27.lol…)
    // -------------------------------------------------------------------------
    companion object {
        const val DEFAULT_URL = "https://fs27.lol"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "frenchstream_settings"
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
                setText(currentUrl())
                hint = "https://…"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de French Stream")
                .setMessage("Le site utilise des adresses tournantes (fs27.lol, french-stream.one…). Indiquez l'adresse actuelle si l'extension ne trouve plus rien (fstream.info l'annonce).")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setSiteUrl(context, DEFAULT_URL) }
                .show()
        }
    }

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    private val baseHeaders get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    // -------------------------------------------------------------------------
    // Accueil & recherche (DLE)
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "films" to "Films (derniers)",
        "films-vf" to "Films VF",
        "series" to "Séries",
        "series-vf" to "Séries VF",
        "animes" to "Animes"
    )

    private fun pageUrl(name: String, page: Int): String {
        val base = when (name) {
            "films-vf" -> "/films/vf/"
            "series-vf" -> "/series/vf/"
            "animes" -> "/animes/"
            "series" -> "/series/"
            else -> "/films/"
        }
        return if (page <= 1) currentUrl() + base else currentUrl() + base.trimEnd('/') + "/page/$page/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val html = runCatching {
            app.get(pageUrl(request.name, page), headers = baseHeaders).text
        }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        return newHomePageResponse(request, items, hasNext = items.size >= 15)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val html = runCatching {
            app.get(
                currentUrl() + "/index.php?do=search&subaction=search&story=" +
                    java.net.URLEncoder.encode(query, "UTF-8"),
                headers = baseHeaders
            ).text
        }.getOrNull() ?: return emptyList()
        return parseCards(html)
    }

    /** Cartes DLE : <a class="short-poster…" href="/index.php?newsid=X" alt="TITRE"> … <img src="poster"> */
    private fun parseCards(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        Regex("""<a\s+class="short-poster[^"]*"\s+href="([^"]+newsid=(\d+))"[^>]*alt="([^"]*)"""")
            .findAll(html).forEach { m ->
                val url = m.groupValues[1]
                val id = m.groupValues[2]
                val title = m.groupValues[3].trim()
                if (title.isBlank()) return@forEach
                // poster : première <img src> après le début de la carte
                val after = html.substring(m.range.last, minOf(html.length, m.range.last + 700))
                val poster = Regex("""<img[^>]+src="([^"]+)"""").find(after)?.groupValues?.get(1)
                val isSeries = url.contains("/series") || title.contains("Saison", true)
                out += if (isSeries) {
                    newAnimeSearchResponse(title, "$mainUrl/index.php?newsid=$id", TvType.Anime) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(title, "$mainUrl/index.php?newsid=$id", TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }
        return out.distinctBy { it.url }
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val newsId = Regex("""newsid=(\d+)""").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("URL French Stream invalide")

        val html = app.get("$mainUrl/index.php?newsid=$newsId", headers = baseHeaders).text
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()?.replace(Regex("\\s*en streaming complet.*$", RegexOption.IGNORE_CASE), "")
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Fiche $newsId"
        val poster = Regex("""data-affiche="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val backdrop = Regex("""data-affiche2="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(
            doc.selectFirst(".facts")?.text() ?: html.substring(0, minOf(html.length, 40000))
        )?.value?.toIntOrNull()

        val isSeries = html.contains("id=\"serie-data\"") || html.contains("id=\"serie-config\"") ||
            title.contains("saison", true)

        if (isSeries) {
            val eps = fetchSeriesEpisodes(newsId)
            if (eps.isEmpty()) throw ErrorLoadingException("Aucun épisode disponible (données de la série illisibles).")
            val byDub = eps.groupBy({ it.first }, { it.second })
                .mapValues { (_, list) -> list.sortedBy { it.episode ?: 0 } }
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.episodes = byDub.toMutableMap()
            }
        }

        // ----- Film -----
        val players = fetchFilmPlayers(newsId)
        if (players.isEmpty()) throw ErrorLoadingException("Aucun lecteur disponible pour ce film.")
        val dataUrl = "$mainUrl/film?id=$newsId"
        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
        }
    }

    /** /engine/ajax/film_api.php?id=X → players par langue. */
    private data class FilmLink(val url: String, val player: String, val lang: String?)

    private suspend fun fetchFilmPlayers(newsId: String): List<FilmLink> {
        val json = runCatching {
            app.get("$mainUrl/engine/ajax/film_api.php?id=$newsId", headers = baseHeaders + mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to "$mainUrl/index.php?newsid=$newsId")).text
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<FilmLink>()
        runCatching {
            val root = mapper.readTree(json)
            root.get("players")?.fields()?.forEach { (player, langs) ->
                langs.fields()?.forEach { (lang, urlNode) ->
                    val u = urlNode.asText()
                    if (u.startsWith("http")) {
                        val langLabel = when (lang) {
                            "vostfr" -> "VOSTFR"; "vff" -> "VF"; "vfq" -> "VFQ"
                            "vf" -> "VF"; "vo" -> "VO"
                            else -> if (lang == "default") null else lang.uppercase().take(8)
                        }
                        out += FilmLink(u, player, langLabel)
                    }
                }
            }
        }
        return out.distinctBy { it.url }
    }

    /** /static/series/{id}.js → {vf:{ep:{player:url}}, vostfr:{...}, vo:{...}} */
    private data class SeriesEp(val lang: String, val dub: DubStatus, val players: Map<String, String>)

    private suspend fun fetchSeriesEpisodes(newsId: String): List<Pair<DubStatus, Episode>> {
        val js = runCatching {
            app.get("$mainUrl/static/series/$newsId.js", headers = baseHeaders).text
        }.getOrNull() ?: return emptyList()
        val root = runCatching { mapper.readTree(js) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<DubStatus, Episode>>()
        for ((langKey, dub) in listOf("vostfr" to DubStatus.Subbed, "vf" to DubStatus.Dubbed, "vo" to DubStatus.Subbed)) {
            val eps = root.get(langKey) ?: continue
            eps.fields()?.forEach { (epNum, players) ->
                val n = epNum.toIntOrNull() ?: return@forEach
                val map = mutableMapOf<String, String>()
                players.fields()?.forEach { (p, v) ->
                    val u = v.asText()
                    if (u.startsWith("http")) map[p] = u
                }
                if (map.isNotEmpty()) {
                    out += dub to newEpisode("$mainUrl/ep?id=$newsId&lang=$langKey&ep=$n") {
                        this.episode = n
                        this.name = if (langKey == "vo") "Épisode $n (VO)" else null
                    }
                }
            }
        }
        return out
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
        syncUrl()
        var found = false
        val lock = Any()
        val doctorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val doctorJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
        val seenUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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

        // --- Collecte des liens (film ou épisode de série) ---
        data class Target(val url: String, val label: String)
        val targets = mutableListOf<Target>()

        val filmId = Regex("""[?&]id=(\d+)""").find(data)?.groupValues?.get(1)
        val epLang = Regex("""[?&]lang=(\w+)""").find(data)?.groupValues?.get(1)
        val epNum = Regex("""[?&]ep=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()

        if (filmId != null && epNum == null) {
            fetchFilmPlayers(filmId).forEach { l ->
                val player = labelFromPlayer(l.player, l.url)
                targets += Target(l.url, l.lang?.let { "$player · $it" } ?: player)
            }
        } else if (filmId != null && epLang != null && epNum != null) {
            val js = runCatching {
                app.get("$mainUrl/static/series/$filmId.js", headers = baseHeaders).text
            }.getOrNull() ?: return false
            val root = runCatching { mapper.readTree(js) }.getOrNull() ?: return false
            val langLabel = when (epLang) { "vostfr" -> "VOSTFR"; "vf" -> "VF"; "vo" -> "VO"; else -> epLang.uppercase() }
            root.get(epLang)?.get(epNum.toString())?.fields()?.forEach { (player, v) ->
                val u = v.asText()
                if (u.startsWith("http")) {
                    val p = labelFromPlayer(player, u)
                    targets += Target(u, "$p · $langLabel")
                }
            }
        }
        if (targets.isEmpty()) {
            doctorScope.cancel()
            return false
        }

        // --- Extraction parallèle ---
        val semaphore = Semaphore(6)
        coroutineScope {
            targets.map { t ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        // liens directs (fsvid/vidzy sont résolus par le fallback générique XOR)
                        if (directStreamRegex.matches(t.url)) {
                            emit(
                                newExtractorLink(t.label, t.label, t.url) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                    this.type = if (".m3u8" in t.url) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                }
                            )
                            return@async
                        }
                        var produced = 0
                        if (Vidara.isVidara(t.url)) {
                            // Famille Vidara : API dédiée /api/stream (appels directs, robuste)
                            runCatching {
                                Vidara().getUrl(t.url, mainUrl, { sub -> synchronized(lock) { subtitleCallback(sub) } }) { link ->
                                    produced++
                                    emit(relabel(link, t.label))
                                }
                            }
                        } else runCatching {
                            loadExtractor(t.url, mainUrl, { sub -> synchronized(lock) { subtitleCallback(sub) } }) { link ->
                                produced++
                                emit(relabel(link, t.label))
                            }
                        }
                        if (produced == 0) {
                            runCatching {
                                genericExtract(t.url, t.label, { sub -> synchronized(lock) { subtitleCallback(sub) } }) { link -> emit(link) }
                            }
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        doctorJobs.forEach { it.join() }
        doctorScope.cancel()
        return found
    }

    /** Nom lisible du lecteur. */
    private fun labelFromPlayer(player: String, url: String): String {
        val p = player.lowercase()
        return when {
            p == "premium" -> "FS Premium"
            p == "filmoon" || "vidara" in url -> "FileMoon"
            p == "vidzy" -> "Vidzy"
            p == "dood" -> "Dood"
            p == "voe" -> "Voe"
            p == "netu" -> "Netu"
            p == "uqload" -> "Uqload"
            p.isNotBlank() -> player.replaceFirstChar { it.uppercase() }
            else -> labelFromUrl(url)
        }
    }

    private fun labelFromUrl(url: String): String {
        val u = url.lowercase()
        return when {
            "uqload" in u -> "Uqload"
            "vidzy" in u -> "Vidzy"
            "fsvid" in u -> "FS Premium"
            "vidara" in u -> "FileMoon"
            "dood" in u -> "Dood"
            "voe" in u -> "Voe"
            "netu" in u || "kakaflix" in u -> "Netu"
            "sibnet" in u -> "Sibnet"
            "vidmoly" in u -> "VidMoly"
            else -> Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1)?.removePrefix("www.") ?: "Lecteur"
        }
    }

    // -------------------------------------------------------------------------
    // Docteur de liens (identique aux autres extensions du dépôt)
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
            if (!res.isSuccessful) {
                val code = res.code
                return@use code == 403 || code == 416 || code == 429
            }
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
            val isFlv = prefix.startsWith("FLV") || prefix.startsWith("OggS") || prefix.startsWith("ID3")
            val isMp3Sync = n >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0
            when {
                link.type == ExtractorLinkType.DASH || ".mpd" in link.url ->
                    prefix.contains("<MPD") || prefix.contains("<?xml")
                link.type == ExtractorLinkType.M3U8 || ".m3u8" in link.url || contentType.contains("mpegurl") ->
                    prefix.contains("#EXTM3U") || contentType.contains("mpegurl")
                contentType.startsWith("video/") || contentType.startsWith("audio/") -> true
                prefix.contains("#EXTM3U") -> true
                isFtyp || isEbml || isTs || isFlv || isMp3Sync || prefix.startsWith("RIFF") -> true
                else -> false
            }
        }
    } catch (e: Exception) {
        true
    }

    private fun relabel(link: ExtractorLink, label: String): ExtractorLink {
        return ExtractorLink(
            link.source,
            label,
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
    // Fallback générique (fsvid.lol / vidzy.cc : sources videojs XOR ; embeds JW)
    // -------------------------------------------------------------------------
    private suspend fun genericExtract(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(embedUrl, referer = mainUrl, headers = mapOf("user-agent" to USER_AGENT)).text

        val links = LinkedHashSet<String>()
        Regex("""(?:og:video(?::secure_url)?|contentUrl|embedUrl)"?\s*(?:content|=|:)\s*["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""(?:file|src|url|source)\s*[=:]\s*["'](https?://[^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        directStreamRegex.findAll(page).map { it.value }.forEach { links.add(it) }
        // p.a.c.k.e.r (JWPlayer obfusqué)
        if (links.isEmpty()) {
            runCatching { JsUnpacker(page).takeIf { it.detect() }?.unpack() }.getOrNull()?.let { unpacked ->
                Regex("""(?:file|src)\s*[=:]\s*["'](https?://[^"']+)["']""")
                    .findAll(unpacked).map { it.groupValues[1] }.forEach { links.add(it) }
                directStreamRegex.findAll(unpacked).map { it.value }.forEach { links.add(it) }
            }
        }
        // Lecteurs videojs obfusqués (vidzy.cc, fsvid.lol…) : src = XOR(base64, hostname)
        Regex("""\}\("([A-Za-z0-9+/=]{40,})"\)""").findAll(page).forEach { m ->
            val host = Regex("""^https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return@forEach
            decodeXorSource(m.groupValues[1], host)?.let { links.add(it) }
        }

        links.asSequence()
            .filter { it.startsWith("http") }
            .map { it.replace("&amp;", "&") }
            .filter { link -> junkFilterRegex.containsMatchIn(link).not() }
            .filter { it.endsWith(".m3u8") || it.endsWith(".mp4") || it.endsWith(".webm") || directStreamRegex.matches(it) }
            .distinct()
            .forEach { link ->
                callback(
                    newExtractorLink(label, label, link) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                        this.type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }

        links.isNotEmpty()
    }.getOrDefault(false)

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

    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")

    private val junkFilterRegex = Regex(
        """(?i)(youtube|youtu\.be|dailymotion|\.jpg|\.jpeg|\.png|\.gif|\.webp|\.svg|\.vtt|\.srt|""" +
            """/ads?/|adserve|adservice|adsystem|doubleclick|banner|/pixel|analytics|/thumb|poster|trailer)"""
    )
}
