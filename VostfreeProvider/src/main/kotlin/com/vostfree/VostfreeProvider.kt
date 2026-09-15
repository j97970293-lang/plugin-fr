package com.vostfree

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
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
class VostfreePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        VostfreeProvider.appContext = context.applicationContext
        registerMainAPI(VostfreeProvider())
        openSettings = { ctx -> VostfreeProvider.showSettings(ctx) }
    }
}

// ===========================================================================
// Vostfree (vostfree.ws / ipv4.vostfree.ws) — DataLife Engine, animes & films
//   · Listes : /animes-vostfr/, /animes-vf/, /films-vf-vostfr/ (+ /page/N/).
//   · Recherche DLE : /index.php?do=search&subaction=search&story=Q (ouverte).
//   · Fiche anime : les lecteurs de CHAQUE épisode sont dans la page :
//     <div id="buttons_{ep}" class="button_box">
//       <div id="player_{pid}" class="new_player_{type}">Label</div>… </div>
//     … <div id="content_player_{pid}" class="player_box">{token|URL}</div>
//     Le type donne l'URL : sibnet → video.sibnet.ru/shell.php?videoid=…,
//     uqload → uqload.io/embed-….html, mytv → myvi.top/embed/… ; si le
//     contenu commence par http c'est l'URL directe (dood, opvid, voe…).
// ===========================================================================
class VostfreeProvider : MainAPI() {

    override var mainUrl = DEFAULT_URL
    override var name = "Vostfree"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Movie)

    // -------------------------------------------------------------------------
    // Réglages
    // -------------------------------------------------------------------------
    companion object {
        const val DEFAULT_URL = "https://vostfree.ws"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "vostfree_settings"
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
                .setTitle("Adresse de Vostfree")
                .setMessage("Si le site déménage (vostfree.ws, ipv4.vostfree.ws…), indiquez sa nouvelle adresse.")
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

    // Site derrière Cloudflare : selon le réseau, la 1re requête peut être
    // défiée. L'intercepteur résout le défi via WebView (automatique pour les
    // challenges JS, un clic pour Turnstile) puis rejoue la requête avec le
    // cookie cf_clearance — les suivantes passent seules.
    private val cfKiller by lazy { CloudflareKiller() }

    private val baseHeaders get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    // -------------------------------------------------------------------------
    // Accueil & recherche
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "vostfr" to "Animes VOSTFR",
        "vf" to "Animes VF",
        "films" to "Films VF & VOSTFR"
    )

    private fun pageUrl(name: String, page: Int): String {
        val base = when (name) {
            "vf" -> "/animes-vf/"
            "films" -> "/films-vf-vostfr/"
            else -> "/animes-vostfr/"
        }
        return if (page <= 1) currentUrl() + base else currentUrl() + base.trimEnd('/') + "/page/$page/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val html = runCatching {
            app.get(pageUrl(request.data, page), headers = baseHeaders, interceptor = cfKiller).text
        }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        return newHomePageResponse(request, items, hasNext = items.size >= 12)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val html = runCatching {
            app.get(
                currentUrl() + "/index.php?do=search&subaction=search&story=" +
                    java.net.URLEncoder.encode(query, "UTF-8"),
                headers = baseHeaders,
                interceptor = cfKiller
            ).text
        }.getOrNull() ?: return emptyList()
        // Les résultats de recherche DLE ont leur propre mise en page
        val results = Regex(
            """<span class="image"><img src="([^"]+)" alt="([^"]+)"[^>]*/></span>\s*<div class="info">\s*<div class="title"><a href="([^"]+)""""
        ).findAll(html).mapNotNull { m ->
            val href = m.groupValues[3]
            val url = if (href.startsWith("http")) href else currentUrl() + href
            var poster = m.groupValues[1]
            if (poster.startsWith("/")) poster = currentUrl() + poster
            val title = m.groupValues[2].trim()
            if (title.isBlank() || !Regex("""/\d+-[a-z0-9-]+\.html$""").containsMatchIn(url)) null
            else newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
        }.toList()
        return results.ifEmpty { parseCards(html) }
    }

    /** <a href="…/{id}-{slug}.html" title="{titre}"> … <img src="{poster}"> */
    private fun parseCards(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        Regex(
            """<a\s+href="((?:https?://[^"]+)?/\d+-[a-z0-9-]+\.html)"[^>]*title="([^"]+)"[^>]*>\s*<img[^>]+src="([^"]+)""""
        ).findAll(html).forEach { m ->
            val href = m.groupValues[1]
            val url = if (href.startsWith("http")) href else currentUrl() + href
            val title = m.groupValues[2].trim()
            var poster = m.groupValues[3]
            if (poster.startsWith("/")) poster = currentUrl() + poster
            if (title.isBlank()) return@forEach
            val slug = url.trimEnd('/').substringAfterLast('/')
            val isFilm = slug.contains("film") || slug.contains("movie") ||
                (!url.contains("anime") && title.contains("film", true))
            out += if (isFilm) {
                newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
            } else {
                newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
            }
        }
        return out.distinctBy { it.url }.take(40)
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val html = runCatching {
            app.get(url, headers = baseHeaders, interceptor = cfKiller).text
        }.getOrNull() ?: throw ErrorLoadingException("Fiche inaccessible")

        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".slide-poster img")?.attr("src")
        val plot = doc.selectFirst(".slide-desc")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(html)?.value?.toIntOrNull()

        // Épisodes + lecteurs (tout est dans la page)
        val episodes = parseEpisodeBlocks(html)

        if (episodes.isEmpty()) throw ErrorLoadingException("Aucun lecteur trouvé sur cette fiche.")

        // Un seul bloc « épisode » → film
        if (episodes.size == 1 && episodes.keys.first() <= 1) {
            return newMovieLoadResponse(title, url, TvType.Movie, dataUrl(url, episodes.keys.first())) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val epList = episodes.keys.sorted().map { n ->
            n to newEpisode(dataUrl(url, n)) {
                this.episode = n
                // pas de vignette d'épisode côté site → poster de la fiche
                this.posterUrl = poster
            }
        }
        val dub = if (title.contains("VF", true) && !title.contains("VOSTFR", true)) DubStatus.Dubbed else DubStatus.Subbed
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.episodes = mutableMapOf(dub to epList.map { it.second })
        }
    }

    private fun dataUrl(articleUrl: String, ep: Int): String =
        mainUrl + "/p?u=" + java.net.URLEncoder.encode(articleUrl, "UTF-8") + "&n=$ep"

    /** {numéro d'épisode → liste de (urlLecteur, label)} */
    private fun parseEpisodeBlocks(html: String): Map<Int, List<Pair<String, String>>> {
        // contenus : {playerId → token/URL}
        val contents = mutableMapOf<String, String>()
        Regex("""<div id="content_player_(\d+)" class="player_box">([^<]*)</div>""")
            .findAll(html).forEach { m -> contents[m.groupValues[1]] = m.groupValues[2].trim() }

        val out = mutableMapOf<Int, MutableList<Pair<String, String>>>()
        // blocs buttons_{ep}
        val blocks = Regex(
            """<div id="buttons_(\d+)" class="button_box">(.*?)(?=<div id="buttons_\d+"|</div>\s*<b class)""",
            RegexOption.DOT_MATCHES_ALL
        )
        blocks.findAll(html).forEach { bm ->
            val epNum = bm.groupValues[1].toIntOrNull() ?: return@forEach
            Regex("""<div id="player_(\d+)" class="new_player_([a-z0-9]+)">([^<]*)</div>""")
                .findAll(bm.groupValues[2]).forEach { pm ->
                    val pid = pm.groupValues[1]
                    val type = pm.groupValues[2]
                    val label = pm.groupValues[3].trim().ifBlank { type.replaceFirstChar { it.uppercase() } }
                    val content = contents[pid] ?: return@forEach
                    val target = buildPlayerUrl(type, content) ?: return@forEach
                    out.getOrPut(epNum) { mutableListOf() }.add(target to label)
                }
        }
        return out
    }

    /** Convertit (type, contenu) en URL d'embed exploitable. */
    private fun buildPlayerUrl(type: String, content: String): String? {
        if (content.startsWith("http")) return content
        if (content.isBlank()) return null
        return when (type) {
            "sibnet", "netu" -> "https://video.sibnet.ru/shell.php?videoid=$content"
            "uqload" -> "https://uqload.io/embed-$content.html"
            "mytv", "myvi" -> "https://www.myvi.top/embed/$content"
            "fembed" -> "https://www.fembed.com/v/$content"
            "cloudvideo" -> "https://cloudvideo.tv/embed-$content.html"
            "uptostream" -> "https://uptostream.com/iframe/$content"
            "vidmoly" -> "https://vidmoly.org/embed-$content.html"
            else -> null
        }
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
        val articleUrl = Regex("""[?&]u=([^&]+)""").find(data)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: return false
        val epNum = Regex("""[?&]n=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: return false

        val html = runCatching {
            app.get(articleUrl, headers = baseHeaders, interceptor = cfKiller).text
        }.getOrNull() ?: return false
        val players = parseEpisodeBlocks(html)[epNum] ?: return false
        if (players.isEmpty()) return false

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

        val semaphore = Semaphore(6)
        coroutineScope {
            players.map { (u, label) ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        if (directStreamRegex.matches(u)) {
                            emit(
                                newExtractorLink(label, label, u) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                    this.type = if (".m3u8" in u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                }
                            )
                            return@async
                        }
                        var produced = 0
                        runCatching {
                            loadExtractor(u, mainUrl, { sub -> synchronized(lock) { subtitleCallback(sub) } }) { link ->
                                produced++
                                emit(relabel(link, label))
                            }
                        }
                        if (produced == 0) {
                            runCatching {
                                genericExtract(u, label, { sub -> synchronized(lock) { subtitleCallback(sub) } }) { link -> emit(link) }
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
            link.source, label, link.url, link.referer, link.quality,
            link.headers, link.extractorData, link.type, link.audioTracks
        )
    }

    // -------------------------------------------------------------------------
    // Fallback générique
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
        if (links.isEmpty()) {
            runCatching { JsUnpacker(page).takeIf { it.detect() }?.unpack() }.getOrNull()?.let { unpacked ->
                Regex("""(?:file|src)\s*[=:]\s*["'](https?://[^"']+)["']""")
                    .findAll(unpacked).map { it.groupValues[1] }.forEach { links.add(it) }
                directStreamRegex.findAll(unpacked).map { it.value }.forEach { links.add(it) }
            }
        }
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
