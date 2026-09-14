package com.flemmix

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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.ExtractorApi
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
class FlemmixPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        FlemmixProvider.appContext = context.applicationContext
        registerMainAPI(FlemmixProvider())
        openSettings = { ctx -> FlemmixProvider.showSettings(ctx) }
    }
}

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
// Flemmix (flemmix.cloud) — DataLife Engine
//   · Listes : /film-en-streaming/, /serie-en-streaming/, /film-ancien/,
//     /saison-complete/ (+ /page/N/).
//   · FILM : les serveurs sont directement dans la page :
//     <a onclick="loadVideo('URL')"><span>Label</span></a> (≈16 serveurs).
//   · SÉRIE (un article par saison) : blocs cachés <div class="ep{N}vs">
//     (VOSTFR) / <div class="ep{N}vf"> (VF) contenant chacun une liste
//     loadVideo('URL') de lecteurs.
//   · Hébergeurs : uqload, vidmoly, voe, filelions, hxfile, ddstream, save,
//     swish, netu, luluvdo, vixeo, vidara… (CS + fallback générique).
//   · Recherche DLE neutralisée par un « bot shield » → best effort POST direct.
// ===========================================================================
class FlemmixProvider : MainAPI() {

    override var mainUrl = DEFAULT_URL
    override var name = "Flemmix"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)


    // -------------------------------------------------------------------------
    // Réglages
    // -------------------------------------------------------------------------
    companion object {
        const val DEFAULT_URL = "https://flemmix.cloud"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "flemmix_settings"
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
                .setTitle("Adresse de Flemmix")
                .setMessage("Si le site déménage, indiquez sa nouvelle adresse.")
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
    // Accueil & recherche
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "film" to "Films",
        "film-ancien" to "Films anciens",
        "serie" to "Séries",
        "saison-complete" to "Saisons complètes"
    )

    private fun pageUrl(name: String, page: Int): String {
        val base = when (name) {
            "film" -> "/film-en-streaming/"
            "film-ancien" -> "/film-ancien/"
            "saison-complete" -> "/saison-complete/"
            else -> "/serie-en-streaming/"
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
        // Le moteur de recherche de flemmix est neutralisé côté serveur :
        // le POST est redirigé vers l'accueil et le GET renvoie « Bot shield
        // active. ». On tente quand même le POST direct (au cas où le shield
        // serait levé un jour) mais sans cfKiller et avec un délai court pour
        // ne jamais ralentir la recherche globale de l'application.
        val data = mapOf(
            "do" to "search", "subaction" to "search", "story" to query,
            "search_start" to "0", "full_search" to "0", "result_from" to "1"
        )
        val html = runCatching {
            app.post(
                currentUrl() + "/index.php",
                data = data, headers = baseHeaders
            ).text
        }.getOrNull() ?: return emptyList()
        if (html.length < 500) return emptyList() // « Bot shield active. »
        // Le site renvoie parfois l'accueil au lieu des résultats : on ne garde
        // la réponse que si le terme recherché apparaît dans les titres.
        val results = parseCards(html)
        val q = query.lowercase()
        return if (results.any { it.name.lowercase().contains(q) }) results else emptyList()
    }

    /** Cartes : <a href="…film-en-streaming/ID-slug.html"><img src="poster" alt="…"><span class="title1">Titre</span> */
    private fun parseCards(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        Regex(
            """<a\s+href="((?:https?://[^"]+)?/((?:film|serie)-en-streaming/\d+-[^"]+\.html))"[^>]*>\s*""" +
                """<img[^>]+src="([^"]+)"[^>]*alt="([^"]*)"[^>]*/>\s*<span class="title1">([^<]+)</span>"""
        ).findAll(html).forEach { m ->
            val href = m.groupValues[1]
            val url = if (href.startsWith("http")) href else currentUrl() + href
            val posterRaw = m.groupValues[3]
            val poster = if (posterRaw.startsWith("http")) posterRaw else currentUrl() + posterRaw
            val title = m.groupValues[5].trim()
            if (title.isBlank()) return@forEach
            val isSeries = url.contains("/serie-en-streaming/")
            out += if (isSeries) {
                newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
            }
        }
        return out.distinctBy { it.url }
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val html = runCatching {
            app.get(url, headers = baseHeaders).text
        }.getOrNull() ?: throw ErrorLoadingException("Fiche inaccessible")
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: Regex("""<title>([^<]+?)\s*(?:&raquo;|»|\|)""").find(html)?.groupValues?.get(1)?.trim()
            ?: url.trimEnd('/').substringAfterLast('/').substringAfter('-').replace('-', ' ')
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: Regex("""(?:poster|image)"?\s*(?:content|=)\s*"([^"]+\.(?:jpg|png|webp))"""").find(html)?.groupValues?.get(1)
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(html)?.value?.toIntOrNull()

        // Serveurs d'un film : tous les loadVideo de la page
        val filmServers = parseLoadVideo(html)
        val isSeries = url.contains("/serie-en-streaming/") || url.contains("/saison-complete/")

        if (isSeries) {
            // Série : blocs ep{N}vs (VOSTFR) / ep{N}vf (VF)
            val epsVs = Regex("""<div class="ep(\d+)vs"""").findAll(html).map { it.groupValues[1].toInt() }.toList()
            val epsVf = Regex("""<div class="ep(\d+)vf"""").findAll(html).map { it.groupValues[1].toInt() }.toList()
            val allEps = (epsVs + epsVf).distinct().sorted()
            if (allEps.isNotEmpty()) {
                val subbed = epsVs.map { n ->
                    n to newEpisode(episodeDataUrl(url, n)) { this.episode = n }
                }
                val dubbed = epsVf.map { n ->
                    n to newEpisode(episodeDataUrl(url, n)) { this.episode = n }
                }
                val byDub = mutableMapOf<DubStatus, List<Episode>>()
                if (subbed.isNotEmpty()) byDub[DubStatus.Subbed] = subbed.map { it.second }
                if (dubbed.isNotEmpty()) byDub[DubStatus.Dubbed] = dubbed.map { it.second }
                return newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.episodes = byDub.toMutableMap()
                }
            }
            // pas d'épisodes → film malgré l'URL
        }

        if (filmServers.isEmpty()) throw ErrorLoadingException("Aucun lecteur trouvé sur cette fiche.")
        return newMovieLoadResponse(title, url, TvType.Movie, filmDataUrl(url)) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun filmDataUrl(articleUrl: String): String =
        mainUrl + "/f?u=" + java.net.URLEncoder.encode(articleUrl, "UTF-8")

    private fun episodeDataUrl(articleUrl: String, ep: Int): String =
        mainUrl + "/e?u=" + java.net.URLEncoder.encode(articleUrl, "UTF-8") + "&n=$ep"

    /** Tous les (url, label) loadVideo d'un fragment HTML. */
    private fun parseLoadVideo(html: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        Regex("""loadVideo\('([^']+)'(?:,\s*this)?\)[^>]*>\s*<span[^>]*>([^<]*)</span>""")
            .findAll(html).forEach { m ->
                val u = m.groupValues[1]
                val label = m.groupValues[2].trim().ifBlank { labelFromUrl(u) }
                if (u.startsWith("http")) out += u to label
            }
        return out.distinctBy { it.first }
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
        val epNum = Regex("""[?&]n=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()

        val html = runCatching {
            app.get(articleUrl, headers = baseHeaders).text
        }.getOrNull() ?: return false

        // épisode : uniquement les blocs ep{N}vs et ep{N}vf
        val pairs: List<Pair<String, String>> = if (epNum != null) {
            val vs = Regex("""<div class="ep${epNum}vs"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: ""
            val vf = Regex("""<div class="ep${epNum}vf"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: ""
            (parseLoadVideo(vs).map { it.first to (labelFromUrl(it.first) + " · VOSTFR") }) +
                (parseLoadVideo(vf).map { it.first to (labelFromUrl(it.first) + " · VF") })
        } else {
            parseLoadVideo(html).map { (u, l) ->
                val lang = if (l.lowercase().contains("vostfr")) " · VOSTFR" else ""
                u to (if (l.contains("·")) l else l + lang)
            }
        }
        if (pairs.isEmpty()) return false

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
            pairs.map { (u, label) ->
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
                            if (Vidara.isVidara(u)) {
                                Vidara().getUrl(u, mainUrl, { sub -> synchronized(lock) { subtitleCallback(sub) } }) { link ->
                                    produced++
                                    emit(relabel(link, label))
                                }
                            } else loadExtractor(u, mainUrl, { sub -> synchronized(lock) { subtitleCallback(sub) } }) { link ->
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

    /** Étiquette lisible d'un lecteur déduite de l'URL. */
    private fun labelFromUrl(url: String): String {
        val u = url.lowercase()
        return when {
            "uqload" in u -> "Uqload"
            "vidmoly" in u -> "VidMoly"
            "vidara" in u -> "Vidara"
            "voe" in u || "rebeccapracticeloss" in u -> "Voe"
            "filelions" in u || "morencius" in u -> "FileLions"
            "hxfile" in u || "xshotcok" in u -> "Hxfile"
            "playmogo" in u -> "DdStream"
            "savefiles" in u -> "SaveFiles"
            "swish" in u || "hanerix" in u -> "Swish"
            "netu" in u || "firestream" in u -> "Netu"
            "luluvdo" in u -> "LuLuTV"
            "vixeo" in u -> "Vidsonic"
            "dood" in u -> "Dood"
            "sibnet" in u -> "Sibnet"
            "filemoon" in u || "moonplayer" in u -> "FileMoon"
            "flemmix.upns" in u -> "FMX"
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
