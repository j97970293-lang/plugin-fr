# TUTO — Créer des extensions CloudStream & analyser des sites de streaming

> **Document écrit pour être lu par une IA** (ou un humain). Il synthétise l'expérience
> réelle de création de 9 extensions CloudStream francophones (AnimoFlix, Zenix,
> WaveWatch, Afterdark, FRAnime, FrenchStream, Flemmix, Vostfree, AnimeSama) et
> de l'analyse reverse-engineering de leurs sites.
> Chaque règle ci-dessous a été **vérifiée par des requêtes réelles** — pas de théorie.
> Structure : règles explicites, URL exactes, pièges constatés, code minimal.
> Si vous êtes une IA chargée de créer/maintenir une extension : lisez tout, appliquez
> les checklists, et **ne jamais supposer — testez**.

---

## 1. RÈGLES FONDAMENTALES (à ne jamais violer)

1. **Toujours vérifier par requête réelle.** curl/python depuis un terminal, jamais
   « ça devrait marcher ». Un site qui répond 200 peut être vide, soft-404, ou servir
   du contenu différent selon l'épisode demandé.
2. **Un lien doit être spécifique au contenu demandé.** Avant d'intégrer une source,
   comparer sa réponse pour 2 épisodes différents (ex. S02E01 vs S02E02) : si les
   URLs sont identiques → la source est **pourrie**, on la retire ou on la filtre.
3. **Une page inexistante peut répondre 200.** (soft-404 : animoflix.to redirige en
   douce vers l'épisode 1 ; zeus renvoie des liens génériques même pour un épisode
   fictif E9999). → Toujours vérifier un marqueur de contenu (titre contenant
   « Épisode {n} », id, etc.).
4. **Ne jamais casser ce qui marche.** Un fournisseur qui marche à 90 % est mieux
   qu'une réécriture risquée. Corrections ciblées, versions incrémentées (v1→v2→v3).
5. **Le datacenter n'est pas l'appareil de l'utilisateur.** Cloudflare bloque souvent
   les IPs datacenter (403) alors que l'appareil réel passe. Un embed 403 depuis un
   serveur peut être utile quand même → best-effort. L'inverse est faux : un embed
   mort (000/404/dns) est mort partout.
6. **Langue affichée sur chaque lien** quand elle est connue (« Filemoon · VOSTFR ») :
   énorme gain d'UX, tri possible (VOSTFR d'abord pour un site VOSTFR, VF d'abord
   pour un site VF).

---

## 2. PIPELINE DE CRÉATION D'EXTENSION CLOUDSTREAM

### 2.1 Outillage

- Modèle : https://github.com/recloudstream/cloudstream (template gradle).
- JDK 21 + Android SDK (platforms;android-35, build-tools;35.0.0).
- Build : `./gradlew make makePluginsJson --no-daemon` → un `.cs3` par module
  `XxxProvider/` + `build/plugins.json`.
- RAM limitée : tuer `[K]otlinCompileDaemon` avant un rebuild si OOM.
- CI GitHub : workflow qui copie les `.cs3` + `plugins.json` sur la branche `builds/`.

### 2.2 Anatomie d'un provider (Kotlin) — le minimum vital

```kotlin
@CloudstreamPlugin
class MonPlugin : Plugin() {                     // Plugin (avec Context), pas BasePlugin
    override fun load(context: Context) {
        MonProvider.appContext = context.applicationContext   // pour les réglages
        registerMainAPI(MonProvider())
        registerExtractorAPI(MonExtracteur())    // extracteur maison éventuel
        openSettings = { ctx -> MonProvider.showSettings(ctx) }  // bouton Réglages
    }
}

class MonProvider : MainAPI() {
    override var mainUrl = "https://exemple.fr"
    override var name = "Exemple"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val mainPage = mainPageOf("/chemin" to "🔥 Section", ...) // chemins SANS domaine

    override suspend fun getMainPage(page, request) : HomePageResponse  // catalogue
    override suspend fun search(query): List<SearchResponse>
    override suspend fun load(url): LoadResponse        // fiche + épisodes
    override suspend fun loadLinks(data, isCasting, subtitleCallback, callback): Boolean
}
```

APIs clés (vérifiées par javap sur cloudstream.jar) :
- `newMovieSearchResponse/newTvSeriesSearchResponse/newAnimeSearchResponse(title, url, type){}`
- `newEpisode(url){ season, episode, name, posterUrl, description, score, runTime }`
  ⚠ champ **`runTime`** (pas `duration`) pour les épisodes ; `duration` existe sur les films.
- `newMovieLoadResponse/newTvSeriesLoadResponse`, `Score.from10(double)`.
- `loadExtractor(url, referer, subtitleCallback, linkCallback)` → extracteurs intégrés
  (Filemoon, Uqload, Vidmoly, **SibNet**, Doodstream… des centaines) + vos extracteurs
  enregistrés. Renvoie le nombre de liens produits via le callback.
- `app.get(url, headers=, params=, referer=, interceptor=cfKiller)` : client HTTP
  suspend ; `CloudflareKiller()` résout les challenges CF via WebView **sur l'appareil**.
- `newExtractorLink(source, name, url){ quality, type, referer, headers }` ;
  `ExtractorLinkType.M3U8 / VIDEO / DASH`.
- Plugin settings : étendre `Plugin()` (pas `BasePlugin`), surcharger
  `load(context: Context)`, affecter `openSettings = { ctx -> ... }` → un bouton
  « Réglages » apparaît sur la fiche de l'extension. Stocker l'URL dans
  `SharedPreferences`, la relire à CHAQUE requête (`syncUrl()` en tête de
  getMainPage/search/load/loadLinks) pour que le changement prenne effet sans recharger.

### 2.3 Pattern « plein de serveurs » (prouvé sur 9 extensions)

```
loadLinks(data) :
  1. parser l'id du contenu + saison/épisode depuis data
  2. lancer EN PARALLÈLE (coroutineScope + async(Dispatchers.IO)) :
       - N agrégateurs (chacun dans runCatching → emptyList en cas d'échec)
       - extracteur HLS direct (1embed) → callback immédiat
       - secours spécialisé (ex. animoflix pour les animes)
  3. dédupliquer par URL (LinkedHashMap), trier par (priorité langue ×10 + priorité origine)
  4. liens « direct=true » (playlists .m3u8) → émettre immédiatement
  5. les autres → loadExtractor (Sémaphore(6) pour la parallélisation), et si
     produced == 0 → extraction générique de secours (regex og:video, <source>,
     file:…, .m3u8/.mp4/.webm) avec FILTRE ANTI-JUNK (images, pubs, youtube, .srt…)
```

---

## 3. ANALYSER UN SITE DE STREAMING (méthode de recon)

### 3.1 Ordre d'attaque

1. **Ouvrir le site** (curl, UA navigateur). Noter : statut, CF ?, SSR (données dans
   le HTML) ou CSR (rendu JS → chercher les API).
2. **Si SSR PHP classique** (Zenix) : parser le HTML directement (Jsoup). Chercher
   les endpoints `ajax/` (souvent un JSON de recherche : `zenix.best/ajax/search/suggest?q=`).
3. **Si SPA/bundles JS** (Afterdark : React+TanStack) :
   - télécharger les bundles (`index-*.js`, `src-*.js`) listés dans le HTML ;
   - `grep` les routes API (`/api/...`), les mots-clés (`m3u8`, `sources`, `referer`,
     `token`, `proof`), les URLs d'embeds de secours ;
   - reconstituer le flow : beaucoup de sites ont UNE API cachée `POST /api/sources
     {token, titleKey}` protégée par **Cloudflare Turnstile** → preuve signée exigée
     (header `x-nabi-proof`), impossible à forger sans navigateur réel. NE PAS
     INSISTER : pivoter vers les lecteurs de secours du site (souvent publics).
4. **Tester les gates** : proof absent/invalide → 403 ? POST avec token bidon →
   `{ok:false}` ? Alors abandonner cette voie (vérifié sur Afterdark).
5. **Chercher les fallbacks publics** : les bundles contiennent presque toujours des
   lecteurs externes de secours (videasy, frembed, peachify…) avec leurs URLs
   exactes + paramètres → ce sont des sources LÉGITIMES (le site lui-même les utilise).

### 3.2 Recon d'un embed public (lecteurs TMDB-keyed)

Format quasi universel : `https://lecteur.tld/embed/tv/{tmdb}/{saison}/{épisode}`
ou `/embed/movie/{tmdb}`. Test systématique :
```
pour chaque lecteur :
  GET /embed/movie/{id connu}       → 200 + contenu ?
  GET /embed/movie/{id bidon}       → différence ? (sinon soft-404 constant)
  depuis datacenter ET si possible avec referer du site
```
Lecteurs vivants (testés 2026-09) : player.videasy.net, vidfast.pro,
vidsrc.wtf/api/{2|3}/tv|movie, 2embed.cc/embedtv, 111movies.com, braflix.win,
vidking.net, frembed.skin, 1embed.cc, peachify.top & vidsrc.cc (CF: 403 datacenter,
OK appareil réel). Morts : vidsrc.xyz/.icu, moviesapi.club, vidora.su,
multiembed.mov, smashy, anyembed. **Re-tester avant chaque publication** — ça meurt vite.

### 3.3 Analyser un lecteur JS (1embed.cc — cas réel)

1. `curl https://1embed.cc/embed/movie/27205` → HTML Next.js.
2. Le payload contient (échappé `\"` et `\u0026`) : soit l'ancien format
   `{"name":"Solari","url":"/v/solari_x.m3u8","type":"hls"}`, soit le nouveau
   `{"name":"Necro","url":"https://abdx.tv/hls-proxy?url=<enc>&referer=<enc>","type":"hls"}`.
3. Regex `""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""` + un-escape.
4. **Vérifier la jouabilité** du proxy : GET direct → `application/vnd.apple.mpegurl`
   + `#EXTM3U` = bon. Le proxy gère lui-même le referer → utiliser l'URL telle quelle.
   Les formats CHANGENT (1embed a changé en sept. 2026) → écrire les extracteurs
   pour supporter ancien ET nouveau format.

### 3.4 TRIAGE D'UNE LISTE DE SITES (cas réel : 9 URLs fournies d'un coup)

Quand l'utilisateur donne une liste de sites, NE PAS tout intégrer : évaluer chaque
site en ~5 requêtes et classer. Méthode éprouvée (14 sept. 2026, 9 URLs) :

| Test | Décision si échec |
|---|---|
| 1. Accueil : statut, Cloudflare/Turnstile, SSR ou SPA ? | Turnstile obligatoire → ÉCARTER (dulourd.hair) |
| 2. Recherche : GET puis POST `do=search` (DLE) — résultats contiennent-ils le terme ? | POST redirigé vers l'accueil + GET « Bot shield active. » (18 octets) = recherche MOLLE côté serveur → extension sans recherche (flemmix) |
| 3. Un film connu : où sont les lecteurs ? (iframe, div inline, API JSON, script JS) | Lecteur verrouillé par anti-bot dédié (veske.io sur purstream.ad) → ÉCARTER |
| 4. SPA ? Chercher UNE API de données dans les chunks (12 chunks max, sinon abandon) | Next.js App Router : RSC flight = loading boundaries VIDES, aucun endpoint (animesite.fr) → ÉCARTER |
| 5. Le site n'apporte-t-il QUE des lecteurs déjà couverts ? | 1jour1film = agrégateurs TMDB (vidsrc, moviesapi, vidfast…) déjà dans nos extensions → ÉCARTER (redondant) |

Autres leçons de triage :
- **fstream.info** (et compagnie) : ce sont des PAGES D'ANNONCE qui redirigent vers
  le domaine éphémère courant (fs27.lol). Suivre la redirection et intégrer LE
  domaine courant — et toujours prévoir le bouton Réglages pour changer l'adresse
  (ces domaines meurent en quelques semaines).
- **movix.online ≈ movix.men** : même backend (api.movix.*). Avant d'intégrer un
  « nouveau » site, comparer son contenu/API aux agrégateurs déjà branchés.
- Domaines éphémères ⇒ TOUJOURS l'URL en réglage (`syncUrl()` à chaque requête).

### 3.5 SITES DLE (DataLife Engine) — french-stream (fs27.lol) & vostfree.ws

Le CMS DLE se reconnaît à `index.php?do=search`, `/page/N/`, `uploads/posts/`.
Deux sites DLE, deux stratégies :

**french-stream (fs27.lol)** — tout est en JSON/JS, presque pas de HTML à parser :
- Cartes : `<a class="short-poster…" href="…index.php?newsid={id}" alt="{titre}">`.
- FILM : `GET /engine/ajax/film_api.php?id={newsId}` → `{players:{nom:{vostfr,
  vff, vfq, default:url}}, meta:{affiche, trailer}}` (une API par fiche !).
- SÉRIE : `GET /static/series/{newsId}.js` → `{vf:{"1":{vidzy,uqload,…}}, vostfr:…}`
  (clé = numéro d'épisode ; vfq = VFQ ; vo = VO→Subbed « VO »).
- Saisons : `/engine/ajax/get_seasons.php?serie_tag={tagz}&news_id={id}` avec
  `tagz = f-{tmdb}` / `s-{tmdb}` (fiches alignées TMDB).
- Recherche : `?do=search` classique, cartes identiques au catalogue.

**vostfree.ws** — 1 article = 1 anime COMPLET, lecteurs en divs cachés :
- `content_player_{pid}` : contenu brut (id sibnet, uqload, ou URL http directe) ;
- `player_{pid} class="new_player_{type}"` : type d'hébergeur (sibnet, uqload,
  mytv, dood, voe, opvid, vidmoly, fembed, cloudvideo, uptostream…) ;
- `buttons_{ep}` : bloc PAR ÉPISODE qui référence les player_{pid}.
- ⚠ le parse naïf `(.*?)</div>` CASSE (divs imbriqués) → re.split sur
  `<div id="buttons_\d+" class="button_box">` ou regex avec lookahead
  `(?=<div id="buttons_\d+"|$)`.
- Mapping type→URL : sibnet `video.sibnet.ru/shell.php?videoid={id}`, uqload
  `uqload.io/embed-{id}.html`, mytv/myvi → `myvi.top/embed/{id}`, fembed…
- ⚠ La page de RÉSULTATS DE RECHERCHE DLE n'a PAS la même structure que le
  catalogue : `<span class="image"><img src alt></span><div class="info"><div
  class="title"><a href>` — ne pas oublier le `</span>` fermant dans la regex
  (erreur réelle : 0 résultat à cause de lui).

### 3.6 LECTEURS INLINE & SAISONS EN JS — flemmix.cloud & anime-sama.to

**flemmix.cloud** (DLE déguisé) :
- Films : lecteurs INLINE `loadVideo('https://…')` + `<span>` libellé — jusqu'à
  16 par film (Vidara, Voe, LuLuTV, Uqload…). Regex :
  `loadVideo\('([^']+)'(?:,\s*this)?\)[^>]*>\s*<span[^>]*>([^<]*)</span>`.
- Séries : 1 article par saison, divs `ep{N}vs` (VOSTFR) et `ep{N}vf` (VF).
- Recherche neutralisée (cf. §3.4) → catalogue paginé seulement (30/page).

**anime-sama.to** :
- Recherche : `POST /template-php/defaut/fetch.php` (form `query={q}`) →
  `<a class="asn-search-result" href="/catalogue/{slug}">` + `<h3>` + img
  `cdn.jsdelivr.net/gh/Anime-Sama/IMG@img/contenu/thumb/{slug}.webp`.
- Saisons : appels JS dans la fiche → `panneauAnime("Saison 1", "saison1/vostfr")`
  (chemin RELATIF à `/catalogue/{slug}/`, page saison AVEC slash final).
  Attention aux sagas : « Saga 1 (East Blue) » ne matche pas `saison|season\d+`
  → seasonNumber null, on garde le NOM complet dans le titre d'épisode.
- Épisodes : `<script src='episodes.js?filever=N'>` en SIMPLE quotes RELATIF →
  URL = `{url_saison}/episodes.js` → variables `eps1 = ["…", "…"]` (miroirs),
  `eps1_nb = N` ; les films ont leurs propres tableaux.
- Slug avec POINTS (`2.43-seiin-…`) → regex `[a-z0-9.-]+` PAS `[a-z0-9-]+`
  (erreur réelle : 46 cartes sur 47).
- Lecteurs : AnsEmbed (JWPlayer `sources:`) — réutiliser JwPlayerHelper.

### 3.7 API DE LECTEURS DÉCODÉES (reverse d'embeds publics)

**Vidara (famille « StreamUp », 17 domaines)** — API RÉELLE (sept. 2026) :
- L'ancienne `vidara.to/api/source/{id}` est MORTE (404). La vraie :
  `POST {domaine}/api/stream` body JSON `{"filecode":"{id}","device":"web"}`
  → `{streaming_url: "https://…/master.m3u8?token=…", title, subtitles[], default_sub_lang}`.
- `vidaraa.cc/e/{id}` n'est qu'un miroir HTML qui redirige (var MIRROR) — l'API
  répond sur le domaine du lien lui-même : prendre `Regex("^(https?://[^/]+)")`
  du lien reçu, PAS un domaine en dur.
- Domaines : vidara.to, vidaraa.cc, vidaraw.com, vidarax.cc, vidara.so,
  vidavaca.net, vidaarax.net/com, vidaratem.com, odysseusa.cc, handfacesnap.cc,
  namefacesnap.cc, thebesthosterv.com, vidmatrixa.com, vidchampions.com,
  antarcticadocs.com, nameitweb.com (liste à compléter en greppant les bundles).

**MoviesApi (moviesapi.to ≡ vidspark.to)** — SPA React/vidstack, MAIS l'API
interne est dans le bundle (`grep "api/vidora"` sur index-*.js) :
- `GET /api/vidora/v1/movie/{tmdb}` et `/api/vidora/v1/tv/{tmdb}/{s}/{e}`
  avec header `x-player-key: 3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13`
  → `{result, sources:[{url, tracks:[{file,label}]}], title, view_type, view_id}`.
- Couverture PARTIELLE (beaucoup de « No Vidora link found » / 404 « Movie not
  found ») et 502 fréquents → intégrer en best-effort silencieux : échec =
  `emptyList()`, jamais de lien mort affiché.
- Le pattern général : une SPA « sans API » a presque toujours SON API dans le
  bundle principal — grepper `"/api/`, `fetch(`, `x-*-key` AVANT de déclarer
  « impossible ».

**Movix** : `api.movix.cash` (historique) et `api.movix.men` (2026) partagent le
même backend → fallback en cascade : essayer .cash, si exception/404 réessayer
l'URL avec `.replace("api.movix.cash", "api.movix.men")`.

**VidNest** : extracteur INTÉGRÉ à CloudStream (`VidNest.class` dans le jar) →
`loadExtractor` le gère nativement, il suffit d'émettre l'URL embed
`vidnest.fun/{movie|tv}/{tmdb}[/s/e]`.

---

## 4. LA STACK « AGRÉGATEURS TMDB » (sources multi-serveurs)

Tous sont keyés par ID TMDB — réutilisables pour N'IMPORTE QUEL site de streaming
ayant un catalogue TMDB. URLs exactes (testées 2026-09) :

| Source | URL | Format | Fiabilité épisode (test E01 vs E02) |
|---|---|---|---|
| apiwiflix | `apis.wavewatch.top/apiwiflix.php?id={tmdb}&season={s}&episode={e}` | `allSources=[{url,name,language}]` dans le HTML | ✅ 100 % spécifique |
| zeus (SSE) | `apis.wavewatch.top/zeus.php?sse&type=tv&id={s}&s={s}&e={e}` | NDJSON `data: {"sources":[{url,quality,lang,format,iframe,premium}]}` | ✅ spécifique (blinkflux/cinefuse passent s/e dans l'URL) |
| movix | `api.movix.cash/api/tmdb/tv/{id}?season=&episode=` \| `/movie/{id}` | JSON `player_links[{decoded_url,quality,language}]` | ✅ 100 % spécifique |
| fstream | `api.movix.cash/api/fstream/movie/{id}` | JSON `players{VF:[{url,player}],VOSTFR:…}` (films) | ✅ (films seulement, TV=404) |
| playerix | `apis.wavewatch.top/playerix.php?type=tv&id={s}&season=&episode=` | boutons `<button data-url="…u={base64url}">` + `data-fmt="m3u8"` + `data-alive` | ⚠️ **TV : seuls les boutons `data-fmt="m3u8"` sont spécifiques** ; les iframes sont IDENTIQUES pour tous les épisodes → filtrer ! Films : tout bon |
| mouve | `apis.wavewatch.top/mouve.php?json=1&type=&id=&s=&e=` | JSON `streams[{url,quality,lang,format,iframe}]` | ⚠️ **TV : ~2/3 des liens identiques entre épisodes → films seulement** |

Règles :
- Langues des agrégateurs : `vf/vff/vfq/vo/vostfr/multi` (apiwiflix : `language` ;
  zeus : `lang` ; playerix : badge `class="lang"` → dernier mot du texte).
- 404 anti-rafale TRANSITOIRES si requêtes parallèles ×6 → re-tester en séquentiel ;
  dans le code, `runCatching` + redondance suffisent (ne pas « réparer »).
- Le décodage playerix : base64 **URL_SAFE** du param `u=` de `data-url`.

### 4.1 Le cas particulier des ANIMES LONGS (One Piece & co)

**Problème constaté** : TMDB numérote One Piece en ABSOLU par saison
(S23 = E1156..E1181), les agrégateurs ne l'ont pas du tout (« Serie non trouvee »),
et les conventions divergent partout.

**Solution éprouvée — secours animoflix.to** :
1. animoflix.to a les derniers épisodes VOSTFR (hébergeurs : ansembed.net = HLS
   JWPlayer, video.sibnet.ru = extracteur SibNet intégré à CloudStream).
2. Autocomplétion : `animoflix.to/search-autocomplete.php?q={titre}` →
   `[{title, slug}]`. Match : titre normalisé (minuscules, sans accents/ponctuation).
3. Structure : `/anime/{slug}/saison-{n}/{vf|vostfr}/episode-{k}` où k = **numéro
   dans la saison** (pas absolu). Vérifié : découpage saisonnier identique à TMDB
   sur One Piece S1→S22 (comptes égaux à l'épisode près).
4. Calcul : `sIndex = numéroTMDB - premierNuméroDeSaison + 1` ;
   `absIndex = position cumulée` (pour les animes numérotés en continu).
   Ces deux valeurs sont packées dans le data URL de chaque épisode par load() :
   `/tv/{tmdb}/{s}/{e}?t={titre}&i={sIndex}&a={absIndex}&af={0|1}`.
5. Variantes à essayer dans l'ordre : `saison-{s}/vostfr/episode-{i}` →
   `vostfr/episode-{a}` → `saison-1/vostfr/episode-{a}` (idem vf).
6. **Vérification obligatoire** : le `<title>` de la page doit contenir
   « Épisode {numéro demandé} » (regex insensible aux accents) sinon soft-404 → skip.
   (animoflix redirige les épisodes inexistants vers l'épisode 1 !)
7. Extraire `#epLecteurSelect option[value]` → URLs des lecteurs (+ `data-restricted`).
8. Garde-fou anti-confusion anime/live-action : n'activer que si genre TMDB
   « Animation » OU ≥ 60 épisodes au total (évite de servir l'anime « One Piece »
   quand l'utilisateur regarde le live-action Netflix du même nom).
9. CF : passer `CloudflareKiller()` en interceptor sur les requêtes animoflix.

---

## 5. PIÈGES RENCONTRÉS (leçons réelles)

| Piège | Symptôme | Parade |
|---|---|---|
| Turnstile/proof signé | 403 / `{ok:false}` sur l'API du site | Ne pas forcer ; lecteurs de secours du site + agrégateurs |
| Liens non spécifiques à l'épisode | « ça lit le mauvais épisode » | Test E01 vs E02 avant intégration (cf. §4) |
| Soft-404 | Page 200 mais contenu d'un autre épisode | Vérifier « Épisode {n} » dans le titre |
| Numérotation absolue vs par saison | 0 résultat ou mauvais épisode sur animes longs | Packer sIndex/absIndex + variantes (cf. §4.1) |
| Anti-bot polling | `{"status":"wait"}` infini (vidsrc.buzz) | Abandonner cette source |
| WASM crypto | `/fu.wasm`, liens chiffrés côté client (vidlink) | Abandonner |
| API d'embed down | 522 (api.videasy.net) | Abandonner, garder player.videasy.net |
| HTML avec octet nul | grep/file casse | `grep -a`, python `errors='replace'` |
| Extracteur générique 0 flux | embeds CSR (JS pur) | Les passer à `loadExtractor` best-effort ; ne pas promettre |
| Mauvais lecteurs dans la liste | « container unsupported » | Regex anti-junk (jpg/png/vtt/youtube/ads/poster/trailer) |
| OOM gradle (RAM 2 Go) | build tué | `pkill -9 -f "[K]otlinCompileDaemon"` avant rebuild |
| GitHub: `body is not an object` (422) | release API refuse | Écrire le JSON payload dans un fichier, `curl -d @file` |
| Permissions gradlew perdues | `Permission denied` | `chmod +x gradlew` après restauration de workspace |
| API CloudStream | champs/settings inconnus | `javap -classpath jar com.lagradost.cloudstream3.…` (vérifier AVANT de coder) |
| `JsUnpacker.unpackAndCombStr` inexistant | compilation KO « Unresolved reference » | API réelle : `JsUnpacker(page).takeIf { it.detect() }?.unpack()` (vérifié par javap sur le jar) |
| Label de lambda incorrect | « Unresolved label » (`return@findAll`) | Dans `x.findAll(h).forEach { }` le label est `@forEach` — vérifier le nom du récepteur |
| Import ExtractorApi au mauvais endroit | « Unresolved reference 'ExtractorApi' » | `com.lagradost.cloudstream3.utils.ExtractorApi` (pas à la racine cloudstream3) |
| Regex slug trop stricte | cartes manquantes silencieusement (46/47) | Slugs avec points `2.43-…` → `[a-z0-9.-]+` ; toujours comparer nb de liens trouvés vs nb de liens dans la page |
| Page de recherche ≠ page catalogue | 0 résultat alors que la recherche répond 200 | Les résultats DLE ont leur propre template → parser SPÉCIFIQUE pour la recherche (cf. §3.5) |
| Recherche « qui marche » mais renvoie l'accueil | résultats identiques pour tout terme | Vérifier que le TERME apparaît dans les titres renvoyés, sinon ignorer la réponse (flemmix) |
| Fallback domaine d'API mort | 404 systématique sur un agrégateur | Double domaine en cascade (.cash → .men) ; `runCatching` + `getOrNull() ?: return emptyList()` |
| Vidara « /api/source » obsolète | extractor 404 | API actuelle : POST /api/stream {filecode, device} (cf. §3.7) |
| Anti-bot « veske.io » (purstream.ad) | player verrouillé côté serveur | Écarter — aucune parade sans navigateur |
| Turnstile sur TOUTE la navigation (dulourd.hair) | même l'accueil exige le challenge | Écarter (CloudflareKiller ne gère pas Turnstile) |
| Next.js RSC flight vide (animesite.fr) | loading boundaries `[]`, aucun endpoint | Scanner ≤12 chunks ; si aucune API de données → Écarter |

---

## 6. PUBLICATION (pipeline complet)

1. `./gradlew make makePluginsJson` → `.cs3` par provider + `plugins.json`.
   ⚠ En sandbox RAM 2 Go : builder UN MODULE À LA FOIS et JAMAIS `makePluginsJson`
   (OOM). Commande validée (~30 s/module) :
   `JAVA_HOME=… ANDROID_HOME=… GRADLE_USER_HOME=… ./gradlew :{Module}:make --no-daemon
   --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1024m -Dkotlin.daemon.jvm.args=-Xmx640m`
   ⚠ Ne PAS pousser manuellement la branche `builds/` si un workflow existe : la CI
   force-push (`--amend`) et `cancel-in-progress` annule les runs concurrents →
   simplement pousser main, attendre `conclusion: success` via l'API, vérifier.
2. Copier les `.cs3` dans `releases/` ; `plugins.json` avec
   `"url": "https://raw.githubusercontent.com/{user}/{repo}/builds/{Name}.cs3"`.
3. `repo.json` : `{name, description, manifestVersion:1, pluginLists:[url plugins.json]}`.
4. README : tableau des extensions + versions + notes + miroirs.
5. Commit/push main → CI (GitHub Actions) → attendre le statut `success` via l'API.
6. Release **prerelease:true** (tag `xxx-vN`) + uploader les `.cs3` en assets
   (POST `uploads.github.com/.../assets?name=`).
7. Pousser `.cs3` + `plugins.json` sur la branche `builds/` (c'est CE que
   plugins.json référence).
8. Miroirs x0.at : `curl -F "file=@x" https://x0.at/` pour repo.json, plugins.json,
   chaque .cs3 ; vérifier le sha256 du miroir == local.
9. Réponse à l'utilisateur avec : lien release, liens miroirs, changelog.

---

## 7. CHECKLIST FINALE (avant de dire « c'est fini »)

- [ ] Compile sans erreur (`make`), versions incrémentées.
- [ ] Bouton « Réglages » présent si le site a des domaines éphémères (fs27.lol,
      flemmix…, tous en fait) : changement d'URL effectif SANS réinstallation.
- [ ] Regex validées sur le HTML RÉEL sauvegardé (/tmp/recon) : nombre de cartes
      trouvées == nombre de liens présents dans la page (pas 46/47).
- [ ] `plugins.json` : 4 champs (name, version, url raw builds/, repositoryUrl).
- [ ] Recherche : 2-3 titres renvoient les bons contenus.
- [ ] Fiche film + fiche série + liste épisodes (saisons complètes).
- [ ] loadLinks film : ≥ 20 liens, dont HLS directs.
- [ ] loadLinks épisode standard : ≥ 20 liens, **liens différents entre 2 épisodes**.
- [ ] loadLinks anime long (One Piece) : liens du BON épisode, y compris récent.
- [ ] Épisode inexistant → aucun lien faux (soft-404 géré).
- [ ] Les 3-4 extensions précédentes fonctionnent toujours (build intact).
- [ ] CI verte, release Pre-release créée, builds/ poussé, miroirs vérifiés sha256.

---

*Basé sur l'expérience réelle de : AnimoFlix v1→v5 (scraping animoflix.to, CF,
extracteurs AnsEmbed/Odysee, docteur de liens), Zenix v1→v6 (scraping PHP SSR +
boutons serveurs + agrégateurs), WaveWatch v1→v5 (API TMDB maison + wwembed + TV
live + agrégateurs), Afterdark v1→v5 (RE de bundles React/TanStack, gate Turnstile
analysée et contournée par sources publiques équivalentes), FRAnime v1 (API Kitsu,
challenge CF + jeton watch2 chiffré), FrenchStream v1 (DLE film_api/static-series,
Vidara /api/stream, XOR vidzy/fsvid), Flemmix v1 (lecteurs inline loadVideo,
recherche neutralisée par bot shield), Vostfree v1 (blocs buttons_/player_,
346 épisodes One Piece), AnimeSama v1 (panneauAnime, episodes.js, miroirs),
et le triage des 9 sites du 14 sept. 2026 (4 intégrés, 3 enrichis, 5 écartés
pour cause de Turnstile/veske.io/SPA/bot shield/redondance).*
