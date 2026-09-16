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
- ⚠ **PIÈGE DES FAUSSES CATÉGORIES** (14/09 midi) : TOUTES les URLs de flemmix
  (/film-en-streaming/, /serie-en-streaming/, /vf/, genres…) renvoient la page
  d'accueil (200, pas de redirection) — seul le `<title>` change ! La page
  contient TOUJOURS le même carrousel « item » en haut (30 derniers) PUIS le
  listing réel en cartes « mov » (`<div class="mov-i…"><img src=…> … <a
  class="mov-t" href=…>`) : 20/page, pagination /page/N/ valide (p1∩p2 = 0).
  → Pour des sections distinctes : parser UNIQUEMENT les cartes mov et ignorer
  le carrousel ; la pagination se confirme en comparant les IDs page 1 vs 2.

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

### 3.8 SITE NEXT.js SSR « CLEAN » — cinestream.info (cas idéal)

Structure 100 % SSR (Next.js App Router) sans protection — le cas le plus simple :
- Cartes (accueil/genres/recherche, même markup partout) : `<a href="/film/{slug}">
  … <img alt="Affiche du film {titre} en streaming en {langue} - {qualité}"
  src="https://image.tmdb.org/t/p/w185/…">` — le alt contient TOUT (titre+langue).
- Listes paginées : /film-en-streaming/{n}, /films-ajoutes-recemment/{n},
  /films-populaires/{n}, /films/{Genre}/{n} (16 genres), /annee/{YYYY}/{n}.
- Recherche SSR : `/search?q=…` (24 résultats, même markup que les cartes).
- Fiche : og:title `Film {titre} {année} en Streaming`, og:image TMDB,
  synopsis après `Synopsis du film</h3><p>`, boutons lecteurs
  `<button id="{Nom}" aria-label="Lecteur {Nom} pour …">` (~15) + `tmdbid`
  caché dans le payload RSC échappé : `\"tmdbid\":1368337`
  (regex : `tmdbid[\"]*:+(\d+)` — les backslashes du payload piègent les
  regex naïves).
- LECTEURS : `/player/{tmdbid}/{index}` → `<iframe src="URL hébergeur">`
  DIRECTement (Vidara, Voe, uqload, hanerix…) → loadExtractor, zéro décodage.
- Leçon « fausse SPA » : un site Next.js PEUT être 100 % SSR (cinestream) alors
  qu'un autre est 100 % CSR sans API (animesite.fr) — vérifier la présence
  du contenu dans le HTML AVANT de conclure quoi que ce soit.

### 3.9 RÈGLE D'OR DES SECTIONS MAINPAGE : VÉRIFIER LA DISTINCTIVITÉ

Avant de publier, comparer les ENSEMBLES d'identifiants de chaque section :
```
section A vs section B : |ids(A) ∩ ids(B)| doit être ≈ 0
page 1 vs page 2        : |ids(1) ∩ ids(2)| doit être 0 (sinon pagination morte)
```
Erreurs réelles du 14/09/2026 (2 extensions publiées avec sections cassées) :
- FrenchStream : /films/vf/ ≡ /films/ (17/18), /series/vf/ renvoyait des FILMS,
  /animes/ ≡ /series/ → 3 sections identiques sur 5. Les vraies catégories
  étaient les GENRES (/films/actions/…) et /animation-serie-// (double slash !).
- Flemmix : 4 sections = 4 fois la page d'accueil (cf. §3.6).
- Cause racine : les URLs de menu d'un site peuvent exister (200) sans filtrer,
  ou servir une page générique. TOUJOURS tester le CONTENU, jamais le statut.

---

### 3.10 LE PIÈGE `request.name` vs `request.data` (cause racine v3 — 5 extensions)

`mainPageOf("films" to "Films")` remplit DEUX champs : `request.data` = la CLÉ
("films"), `request.name` = le LIBELLÉ ("Films"). **Le routage se fait TOUJOURS
sur `request.data`** — router sur `request.name` ne lève AUCUNE erreur (le
`when` tombe dans le `else`) et toutes les sections affichent la même chose.

```
// ✗ CASSÉ (v2) : tombe toujours dans else → sections identiques
val url = when (request.name) { "films" -> …; "series" -> …; else -> … }
// ✓ CORRECT (v3) :
val url = when (request.data) { "films" -> …; "series" -> …; else -> … }
```
Réveil douloureux du 14/09/2026 (après-midi) : les correctifs « catalogue » v2
étaient JUSTES côté URLs, mais les 5 extensions concernées routaient sur
`request.name` → l'utilisateur voyait encore 10 sections identiques. Les 4
extensions saines (AnimoFlix, Zenix, Afterdark, WaveWatch) routaient toutes sur
`request.data` — la comparaison des 10 fichiers a isolé la cause en 5 minutes.
**Réflexe : grep -n "request.name" */src/**/*.kt avant chaque release.**

### 3.11 SPA Next.js « sans API » — animesite.fr (reverse complet v3)

Un site Next.js/Turbopack peut paraître impénétrable (SSR vide côté serveur)
alors que TOUT est accessible. Méthode qui a marché (14/09/2026) :

1. **r.jina.ai** (`https://r.jina.ai/<url>`) rend le SPA complet : on y voit
   les routes réelles (/search/{q}, /play/{id}/{s}/{e}) et les données.
2. Les données SSR sont dans le **payload RSC** : `self.__next_f.push([1,"…"])`
   — concaténer tous les push, dés-échapper (`\"` → `"`) puis parser avec des
   regex normales. C'est là que dorment les résultats de recherche et le
   JSON-LD des fiches (saisons : `containsSeason:[{seasonNumber,numberOfEpisodes}]`).
3. **Grep des chunks JS** (`/_next/static/chunks/*.js`) : `fetch(" (` révèle les
   endpoints cachés — animesite : `/api/medias?name={trending|added-episode|
   top-rated}&page=N` (listes), `/api/stream/token` (POST → lecteurs).
4. **Déobfuscation des IDs** : le bundle contenait `convertIdForUrl = 12*t*28`
   → l'id interne × 336 = l'id TVDB des URLs. Un coefficient pareil se trouve
   en cherchant le nom de la fonction dans les chunks puis son corps.
5. **Slug exact** : l'URL /{tvdbId}-{slug} exige le slug EXACT (test
   /1698144-nimporte-quoi → fiche vide). Le **sitemap.xml** (2 575 URLs) sert
   de table id→slug fiable, mise en cache par session.
6. **Lecteurs proxifiés** : `POST /api/stream/token {idAndSlugTitle,
   seasonNumber, episodeNumber, playerIndex}` → `{kind, src:"/v/{token}"}` ;
   la page /v/{token} est une page **SibNet proxifiée** contenant
   `player.src([{src:'/v/{hash}/{id}.mp4'}])` → le MP4 réel vit sur
   `https://video.sibnet.ru/v/{hash}/{id}.mp4` (Referer `shell.php?videoid=`
   obligatoire). kind=external = pub → ignorer. La langue (VOSTFR/VF) se lit
   dans le og:title de la page /v/.

### 3.12 RECHERCHE DLE : GET = PIÈGE, POST = VÉRITÉ (+ titres français)

Les sites DataLife Engine (fs27/french-stream…) ont DEUX recherches :
- **GET** `?do=search&story=q` → renvoie le CATALOGUE PAR DÉFAUT (identique
  pour toute requête) : l'extension « donne autre chose ».
- **POST** `/index.php` (do, subaction=search, story, search_start=0,
  full_search=0, result_from=1) → vrais résultats ; requête inconnue →
  page « Aucun résultat » sans carte.
  ⚠️ L'inverse existe aussi : **vostfree.ws** est un DLE où le GET marche et le
  POST renvoie l'accueil → TOUJOURS tester les deux avant de coder.
- **Cookie anti-bot posé par la home** (flemmix v4) : le POST de recherche
  renvoyait « Bot shield active. » (18 octets). En lisant les scripts inline de
  la page d'accueil : `document.cookie = "h_check=" + (10+15) + ";…"` → le
  shield vérifie le cookie **`h_check=25`**. Parade : l'envoyer avec le POST
  (`cookies = mapOf("h_check" to "25")`). → Réflexe : quand une action AJAX est
  bloquée, chercher TOUS les `document.cookie =` du site (cookie JS calculé).
- **Résultats DLE pollués par des recommandations** (flemmix v4) : la page de
  résultats contient un bloc `#no-results-rec` (recommandations masquées,
  affichées si 0 résultat) AVANT les vraies cartes → sauter le bloc (compteur
  de `<div>`/`</div>` équilibrés depuis le marqueur) avant de parser, sinon la
  recherche renvoie des titres hors sujet.

### 3.13 SPA React/Vue : trouver l'API cachée (purstream.ad)

Une page de 2 Ko sans contenu = SPA. L'API est dans le bundle :
1. Repérer `<script src="/assets/index-*.js">` (2 Mo, minifié).
2. Chercher les **tables de routes** : `const Ot={discover:"/",…}` (routes web)
   et `api_xxx:"chemin/relatif"` (routes API) — la base (`https://api…/api/v1/`)
   est concaténée par une fonction `we()`/`fetch()`.
3. Chercher `fetch("https://…` littéraux (ex. `api/v1/check-email`) pour
   confirmer la base et les en-têtes attendus.
4. Tester les endpoints au `curl` : catalogue (`catalog/movies?page=N`),
   recherche (`search-bar/search/{q}`), fiche (`media/{id}/sheet`), sources
   (`stream/{id}`) — si tout répond en JSON sans auth, l'extension est triviale.
   → PurstreamProvider : HLS m3u8 directs, épisodes avec vignettes TMDB.

### 3.14 WordPress/DooPlay : admin-ajax + scripts base64 (1jour1film)

Les sites DooPlay personnalisés cachent souvent leurs données derrière
`/wp-admin/admin-ajax.php` :
- **Catalogue** : `POST action=j1f_catalogue {type, search, page, tri}` →
  `{html, total, pages}` (cartes prêtes à parser).
- **Sources** : jamais dans le HTML ; servies à la demande par
  `action=j1f_get_source&nonce&post_id&idx` (film) et
  `action=j1f_get_ep_source&nonce&season_id&ep_id&idx` (épisode), avec un
  **nonce renouvelé** via `action=j1f_get_nonce`. Les `idx` sont séquentiels ;
  un 404 = plus de sources (sonder 0..N).
- **Données embarquées** : `J1F_POST_ID`, `J1F_SEASON_ID`, `J1F_SRV` (libellés)
  et `j1fEpsData` (épisodes des saisons) vivent dans des `<script
  src="data:text/javascript;base64,…">` → décoder avec
  `android.util.Base64.decode(…, Base64.DEFAULT)` puis regex/JSON.
- **Hébergeurs** : Vidara → `POST {base}/api/stream {filecode, device:"web"}`
  → `streaming_url` (m3u8) ; Lulustream (`luluvdo.com`) → JS packé →
  `JsUnpacker(page).takeIf { it.detect() }?.unpack()` puis `file:"…m3u8"`.
- **ID TMDB caché** (v3) : le lecteur `vp4` du site embarque l'ID TMDB dans le
  script base64 — films : `vp4-{tmdbId}-` ; pages saison : `var tmdb = N; var
  season = M;` (les 4 lecteurs publics du site sont concaténés en JS avec ces
  variables). → sert à alimenter les lecteurs publics/apiwiflix en serveurs
  supplémentaires (cf. section 4).
- **Fallback REST** (v3) : si l'ajax `j1f_catalogue` meurt, le REST WordPress
  `/wp-json/wp/v2/{movies|tvshows}?per_page=60&page=N` (X-WP-Total: 8423)
  reste vivant — cartes sans poster mais catalogue intact. Le rechercher TOUT
  site WordPress : `wp-json` est presque toujours ouvert.
- **Titres français** : les API Kitsu/AniList cherchent en EN/romaji
  (« attaque des titans » → mauvais résultats). Parade Franime v2 : télécharger
  le catalogue du site lui-même (api.franime.fr/api/animes, ~11 Mo, une fois
  par session, parsing en flux Jackson pour éviter les pics mémoire) et
  filtrer sur `titles.fr_fr` en normalisant (NFD, sans accents, mots entiers).
  Repli automatique sur Kitsu si l'API est bloquée par Cloudflare.

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
| **vidsrc.buzz** (v9) | `vidsrc.buzz/embed/{movie\|tv}/{tmdb}[/{s}/{e}]` → jeton `var Q={…}` → `vidsrc.buzz/pl/api.php?a=sources&type=&id=&s=&e=&t={jeton}` → serveurs `[{ref,name}]` → `a=play&ref=&t=` → `{url:"/_stream?id=…"}` | JSON à 2 temps + HLS proxysé `/_stream?id=` (préfixer `https://vidsrc.buzz`) | ✅ spécifique — **couvre aussi les ANIMES** (99071 ✓), plusieurs serveurs, certains 502 → en tester 4 et garder les `a=play` OK |

Règles :
- Langues des agrégateurs : `vf/vff/vfq/vo/vostfr/multi` (apiwiflix : `language` ;
  zeus : `lang` ; playerix : badge `class="lang"` → dernier mot du texte).
- 404 anti-rafale TRANSITOIRES si requêtes parallèles ×6 → re-tester en séquentiel ;
  dans le code, `runCatching` + redondance suffisent (ne pas « réparer »).
- Le décodage playerix : base64 **URL_SAFE** du param `u=` de `data-url`.

### 4.1 API « seasons » incomplète → UNION avec une autre source (Purstream v3)

**Problème constaté** : `media/{id}/seasons` ne renvoyait que la saison 1 pour
certains animes (Naruto : 52 épisodes ; Shippuden : 32 sur 500) alors que
`media/{id}/sheet` → `urls[]` (le manifeste de lecture) liste TOUT.

**Solution** : épisodes = **UNION** `sheet.urls` (regex `/S(\d+)/E(\d+)/` sur les
URLs) ∪ `seasons` (métadonnées : noms, vignettes, résumés). ⚠ La numérotation de
`urls` peut être **CONTINUE** (Naruto S2 = E53-104) : c'est celle qu'attend
l'endpoint `stream/{id}/episode` (S2E53 → 200, S2E1 → 404) — ne PAS renuméroter.

**Règle** : avant de coder une liste d'épisodes, **sonder l'endpoint de lecture**
avec 2 conventions (per-season vs continue) et vérifier les 404 ; croiser
toujours 2 sources différentes quand le site en expose deux.

**Limite site** : certains trous sont réels (Jujutsu Kaisen S1E3-7, Demon Slayer
S1E15 → 404 sur TOUTES les sources) — c'est une absence côté site, pas un bug ;
les lecteurs publics TMDB servent alors de secours.

### 4.2 Numérotation de saisons unique mais LISIBLE (AnimeSama v4)

**Problème constaté** : pour éviter les fusions CloudStream, les saisons
spéciales étaient décalées à des numéros arbitraires (Kai = 101+, hs = 41+,
films = 90) → « One Piece a 100 saisons », illisible.

**Solution — séquentielle** : les saisons normales gardent leur numéro naturel
(1..N), puis films, OAV, hors-séries et Kai continuent à N+1, N+2… (One Piece :
12 sagas, Films 13, OAV 14, hs 15-16, Kai 17-27). Un `TreeSet` de numéros
« utilisés » garantit l'unicité, et le libellé complet reste dans le nom de
saison/épisode (« Kai - Saga 1 (East Blue) · Épisode 5 »).

**Règle** : un numéro de saison est une clé technique UNIQUE mais affichée —
éviter les plages magiques (100+, 300+) au profit d'une suite compacte.

### 4.3 LE PIÈGE `openSettings` : un réglage non câblé n'existe pas (v7)

**Problème constaté** : la v6 livrait `showSettings()` + `PREFS` sur Purstream
et 1JOUR1FILM mais SANS `openSettings = { … }` dans le `Plugin.load()` → le
bouton ⚙ n'apparaissait jamais ; l'utilisateur croit que la fonctionnalité
n'est pas faite. AnimeSama (v3) avait le bon pattern.

**Pattern COMPLET obligatoire** (les 3 morceaux) :
1. `companion object` : `DEFAULT_URL`, `appContext`, `PREFS`,
   `currentUrl()/setSiteUrl()/showSettings()`.
2. `Plugin.load()` : `Provider.appContext = context.applicationContext` PUIS
   `openSettings = { ctx -> Provider.showSettings(ctx) }`.
3. `syncUrl()` (`mainUrl = currentUrl()`) en tête de CHAQUE override
   (getMainPage/search/load/loadLinks), + invalider les caches indexés par URL.

### 4.3b CLOUDFLARE PAR RÉSEAU : CloudflareKiller sur toutes les requêtes du site (v8)

**Problème constaté** : un site peut répondre 200 depuis un réseau et servir un
défi Cloudflare depuis un autre (opérateur/pays) → catalogues vides chez
l'utilisateur alors que tous les tests passent. Symptôme typique : 403 avec
`server: cloudflare` pour les user-agents non-navigateurs (okhttp, curl).

**Solution** : `private val cfKiller by lazy { CloudflareKiller() }` +
`interceptor = cfKiller` sur **toutes** les requêtes vers le domaine du site
(GET **et** POST ; PAS les autres domaines — embeds Vidara/Lulustream, agrégateurs —
pour ne pas mélanger les cookies de clearance). Au 1er défi, WebView résout
(JS auto, Turnstile = 1 clic), le cookie est mémorisé pour la session.

**Règle** : un déploiement « fonctionne chez moi » ne prouve rien — si le site
est derrière Cloudflare, mettre l'intercepteur DÈS la v1, pas après 3 retours
utilisateurs.

### 4.4 POST bloqué ≠ GET bloqué : toujours un fallback GET (v7)

**Problème constaté** : la recherche AnimeSama (POST `fetch.php`) échouait
chez l'utilisateur alors qu'elle marche partout ailleurs — certains
WAF/Cloudflare défient les POST d'IP « suspectes » en laissant passer les GET.

**Solution** : si le POST ne renvoie rien → **GET `/catalogue/?search={q}`**
(le formulaire HTML du catalogue fait la même recherche côté serveur,
cartes identiques à celles de la home). Tester TOUJOURS si le site expose une
variante GET de chaque POST (formulaire HTML, query-string).

**En-têtes** : mimer le site réel aide — jQuery envoie
`X-Requested-With: XMLHttpRequest` ; les WAF notent aussi les requêtes aux
en-têtes trop minimalistes (voir les Sec-Fetch-* en v7 sur 1jour1film, dont
le WAF renvoie 403 aux user-agents okhttp/curl).

**Diagnostic utilisateur** : une section d'accueil VIDE sans message est
inexploitable — lever `ErrorLoadingException` avec un message actionnable
(« changez l'adresse dans les réglages ⚙ ») plutôt que `return emptyList()`.

### 4.5 Le cas particulier des ANIMES LONGS (One Piece & co)

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

### 4.6 TV EN DIRECT via playlist M3U publique (TeleFrance v1)

Quand les nouveaux « sites de streaming » sont des fermes à publicités (faux
lecteurs, /go.php — papystreaming.fr, blablastream.fr, cpasmal.fr, choupox.org
sont du même gabarit), les **playlists IPTV publiques** (iptv-org, GitHub
Pages, aucune protection) offrent une source stable et légale de TV en direct :
- `iptv-org.github.io/iptv/countries/{code}.m3u` : #EXTINF avec tvg-logo +
  group-title, ~215 chaînes pour fr.m3u (~65 % vivantes à un instant T).
- Filtrer `[Geo-blocked]` / `[Not 24/7]` et les noms parasites (certains
  contiennent des user-agents…), nettoyer `(1080p)` du nom affiché.
- CloudStream : `newLiveSearchResponse` + `newLiveStreamLoadResponse` + 
  `ExtractorLinkType.M3U8` direct — cf. WaveWatch (chaînes live) et
  TeleFrance (M3U). Réglage ⚙ = URL de playlist → n'importe quelle liste.

### 4.7 REVERSE D'UN HÉBERGEUR CHIFFRÉ AES-256-GCM (bysezoxexe, v9)

Symptôme : « peu de serveurs » sur 1jour1film — les sources du site
s'appuyaient sur des hébergeurs inconnus de tout extracteur. Méthode :

1. **Identifier le vrai lecteur** : la source `/e/{code}` d'un SPA React
   renvoie une coquille de 1,6 Ko → le vrai code est dans un bundle
   **lazy-loadé** (`videoPagesBundle-*.js`, listé dans le bundle principal).
   C'est lui qu'il faut analyser, pas l'index.
2. **Trouver l'API** : chercher `"/api/…"` dans le bundle : ici
   `GET /api/videos/{code}/` renvoie `playback{algorithm, iv, payload,
   key_parts[30], version, expires_at}`.
3. **Dériver la clé** (reverse des fonctions minifiées `ws`/`ks`/`Ea`) :
   - `Ea(version)` = `[version, 31 - version]` (indices 1-based, valides
     uniquement si ≤ nombre de parts) ;
   - la clé = `base64url(key_parts[v-1]) + base64url(key_parts[30-v])`
     → 32 octets exactement (AES-256) ;
   - fallback si version inconnue : concaténer TOUTES les parts.
4. **Déchiffrer** : AES-256-GCM standard, IV = base64url(playback.iv)
   (12 octets), tag 128 bits → `javax.crypto.Cipher("AES/GCM/NoPadding")`.
   Le JSON clair contient `sources[{url (HLS direct), label, quality}]`.
   Testé en Python (`cryptography`) puis porté en Kotlin — m3u8 200 OK.

Cas cousins rencontrés le même jour (1jour1film séries) :
- **cetaitmieuxavant.website** : page épisode avec
  `const videoData={"servers":[{name,url}×4]}` → relayer chaque serveur
  vers son extracteur (firestream/byse/lulustream… ; p2pstream = non
  extractible, on saute).
- **firestream.site** : `/e/{code}` contient
  `<script id="video-data" type="application/json">{video:{signedVideoUrl,…}}`
  → lien direct. ⚠️ le champ disparaît si l'IP est flaggée VPN
  (`isVpn:true`) — depuis un datacenter on ne voit rien, depuis une IP
  résidentielle ça marche. Ne pas conclure « mort » trop vite.

### 4.8 TV MALGACHE : LIVES YOUTUBE RÉSOLUS À LA VOLÉE (TeleFrance v2)

Les chaînes de Madagascar n'existent dans AUCUNE playlist IPTV publique
(mg.m3u = 1 chaîne, aucune malgache ; Free-TV/IPTV = rien). Mais TVM,
RealTV, RTA, Viva TV, KOLO TV, TV Plus diffusent leurs journaux en direct
sur YouTube — et **CloudStream embarque un extracteur YouTube** (vérifier :
`unzip -l cloudstream.jar | grep -i youtube`).

Méthode (aucune clé API, côté serveur de la page) :
1. `GET youtube.com/channel/{channelId}/live` (le HTML serveur suffit,
   pas besoin de JS) ;
2. premier `"videoId":"…"` de la page = direct en cours (sinon dernière
   vidéo —acceptable pour une chaîne info) ;
3. fiche = `newLiveStreamLoadResponse(nom, url, watchUrl)`,
   lecture = `loadExtractor("https://www.youtube.com/watch?v=…")`.
4. Logos : avatar `yt3.googleusercontent.com` de la chaîne (stable).

### 4.9 UNE IP DATACENTER N'EST PAS UN TÉLÉPHONE (leçon transversale v9)

Trois fois le même piège dans cette version : firestream masque
`signedVideoUrl` aux IP « VPN » (Google LLC), animoflix challenge les
pages épisodes, franime/api renvoie 403. **Toujours tester un lecteur
depuis une vraie IP mobile avant de le déclarer mort**, et encapsuler
dans `runCatching` + garder d'autres serveurs : l'app de l'utilisateur
(Orange/MVola à Tana) ne voit pas le même internet que le sandbox.

### 4.10 LE « TROU NOIR » DES APPLIS WEB PUBLIQUES : LA PASSERELLE SANS AUTH (Streamixx v10)

Une SPA peut sembler opaque (1,3 Ko de coquille React) alors que son
backend est **complètement ouvert** : Streamixx parle à une passerelle
Cloudflare Worker (`…workers.dev/api/…`) qui ne vérifie aucune session.
Méthode : analyser le bundle principal (`/assets/index-*.js`) et lister
les routes backtick `` `/api/…` `` ; tester chaque route GET directement.
Ici : `/api/homepage`, `/api/trending?page&perPage`, `/api/search/{q}`,
`/api/info/{id}` (saisons = `resource.seasons[{se,maxEp}]` → générer
1..maxEp), `/api/sources/{id}?season&episode` (MP4 directs 360/480/720
signés) et `/api/caption?url=` (sous-titres VTT proxysés).
⚠️ Le CDN vidéo (hakunaymatata) renvoie 429 aux IP datacenter — le flux
marche depuis une IP résidentielle (même leçon qu'en §4.9).

### 4.11 EXTENSIONS 18+ : TvType.NSFW ET LES BONNES SOURCES (v10)

- CloudStream embarque `TvType.NSFW` et un réglage « contenu adulte » :
  une extension NSFW déclare `supportedTypes = setOf(TvType.NSFW)` et
  `tvTypes = listOf("NSFW")` dans le build.gradle.kts — elle est alors
  filtrée tant que le réglage est désactivé. Pas de champ `containsNsfw`
  dans le DSL gradle (vérifié dans CloudstreamExtension.class).
- Trois sources vérifiées de bout en bout :
  - **hentaicity.com** (PHP classique) : HLS signé
    `hls.hentaicity.com/…master.m3u8` + MP4 de secours dans la page
    vidéo ; recherche = `/customsearch.php?view=search&search_type=video&search={q}&main_cat=0`.
  - **hentaihaven.xxx** : la section Rule34 expose un **WordPress
    headless** (`cms.hentaihaven.xxx/wp-json/wp/v2/rule34-video?
    _embed=wp:featured_media` — titres + miniatures ; ⚠️ le JSON échappe
    les slashes, déséchapper `\/` avant regex) et le manifeste HLS **en
    clair** dans la page vidéo (`octopusmanifest.org/{uuid}/playlist.m3u8`).
    La section hentai `/watch/` charge son flux côté client → écartée.
    `orderby` WP valide : date, modified, id… (pas « views »).
  - **xvideos.com** : pages publiques sans protection ; manifeste
    `https://hls-cdn77.xvideos-cdn.com/…/hls.m3u8` dans la config
    html5player ; cartes avec `data-src` (vignettes lazy) et titres dans
    `<p class="title">` ; filtrer les hrefs `THUMBNUM`.
- Impasses testées : hanime.tv (403 CF datacenter), eporner
  (`/xhr/video/{id}?hash=` renvoie `available:false` depuis un
  datacenter), hqporner (liens internes introuvables côté serveur).

### 4.12 FRANCOPHONIE SANS DOUBLONS : UNION DE PLAYLISTS (TeleFrance v3)

Ajouter des chaînes sans répéter le catalogue : charger la playlist
langue `iptv-org.github.io/iptv/languages/fra.m3u` (Afrique, Belgique,
Canada…) et **exclure les noms déjà présents** dans la liste France
(comparaison sur le nom nettoyé en minuscules), en imposant une section
dédiée (`forcedSection`) aux nouvelles. Les playlists pays
(`/countries/{code}.m3u`) et langue (`/languages/fra.m3u`) existent ;
les playlists continent (`regions/afr.m3u`) n'existent pas.

### 4.13 LES BLOGS « BONS PLANS STREAMING » SONT DES LISTES D'APKS (v10)

La page haloule.com (films/séries/animes) ne référence **aucun site
scrapable** : que des APKs (Netflix Mirror, MovieBox, FreeCine…),
des raccourcisseurs (urlr.me, cuty.io) et des outils (VLC, Web Video
Caster). Bowd (bowdtv.com, « TV + films + séries gratuit ») est une
Expo app derrière une auth better-auth + Turnstile → 401 sur toutes
les routes API. Avant d'investir : vérifier en 2 requêtes si l'API
exige une session.



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
| **Router les sections sur `request.name`** | **10 sections identiques, silencieux** | **Toujours `request.data`** (cf. §3.10) — grep avant release |
| Recherche DLE en GET | « la recherche donne autre chose » | POSTer /index.php (ou l'inverse selon le site — tester les deux, cf. §3.12) |
| meta description = nom de fichier | fiche avec « trucs bizarres » (flemmix : « stream-vf-….jpg 1h 40min ») | Chercher le synopsis structuré (bloc « Synopsis: » même commenté) |
| Bloc épisode `ep00vs` | épisode 0 fantôme sans lecteurs | Filtrer `it > 0` sur les numéros extraits |
| Slug d'URL construit au jugé | fiche vide (404 soft Next.js) | Sitemap.xml = table id→slug exacte (cache session) |
| IDs internes ≠ IDs publics | URL introuvable | Chercher la fonction de conversion dans les chunks (× 336 ici) |
| Titres FR vs API EN | recherche « ne trouve rien » en français | Catalogue du site lui-même (titres fr_fr) + normalisation NFD |
| ExtractorLink deprecated en erreur | build KO avec newExtractorLink conseillé | `newExtractorLink(name, label, url) { this.type = …; this.referer = … }` |
| Import ExtractorApi au mauvais endroit | « Unresolved reference 'ExtractorApi' » | `com.lagradost.cloudstream3.utils.ExtractorApi` (pas à la racine cloudstream3) |
| Regex slug trop stricte | cartes manquantes silencieusement (46/47) | Slugs avec points `2.43-…` → `[a-z0-9.-]+` ; toujours comparer nb de liens trouvés vs nb de liens dans la page |
| Page de recherche ≠ page catalogue | 0 résultat alors que la recherche répond 200 | Les résultats DLE ont leur propre template → parser SPÉCIFIQUE pour la recherche (cf. §3.5) |
| Recherche « qui marche » mais renvoie l'accueil | résultats identiques pour tout terme | Vérifier que le TERME apparaît dans les titres renvoyés, sinon ignorer la réponse (flemmix) |
| Fallback domaine d'API mort | 404 systématique sur un agrégateur | Double domaine en cascade (.cash → .men) ; `runCatching` + `getOrNull() ?: return emptyList()` |
| Vidara « /api/source » obsolète | extractor 404 | API actuelle : POST /api/stream {filecode, device} (cf. §3.7) |
| Anti-bot « veske.io » (purstream.ad) | player verrouillé côté serveur | Écarter — aucune parade sans navigateur |
| Turnstile sur TOUTE la navigation (dulourd.hair) | même l'accueil exige le challenge | Écarter (CloudflareKiller ne gère pas Turnstile) |
| Next.js RSC flight vide (animesite.fr) | loading boundaries `[]`, aucun endpoint | Scanner ≤12 chunks ; si aucune API de données → Écarter |
| **`@CloudstreamPlugin` posé sur le provider** (AnimeSite v1) | **« installe avec erreur » puis extension INVISIBLE** (aucune classe Plugin chargée) | Toujours une classe `XxxPlugin : Plugin()` séparée qui fait `registerMainAPI(XxxProvider())` — comparer le `pluginClassName` du manifest avec un module qui marche |
| **Fusion d'épisodes par (saison, épisode)** (AnimeSama v1, One Piece) | une série longue n'affiche que ~74 épisodes (« pas complète ») | CloudStream fusionne les épisodes de même couple (season, episode) : si les saisons du site n'ont pas de numéro (« Saga N », `saisonNhs`…), leur attribuer un numéro UNIQUE par panneau (100+N pour « Kai », 41+ pour hs, 90 films, 91 OAV…) + nom d'épisode explicite |
| Bot shield exigeant un cookie JS | POST recherche → « Bot shield active. » | Lire les `document.cookie =` des scripts inline de la home (h_check=25 chez flemmix) et renvoyer le cookie |
| Recommandations cachées dans les résultats | la recherche renvoie des titres hors sujet | Sauter le bloc `#no-results-rec` (divs équilibrés) avant de parser (flemmix) |
| SPA React sans contenu HTML | page de 2 Ko, rien à scraper | Reverser le bundle : tables de routes `api_*`, `fetch("https://…")` → API JSON souvent ouverte (purstream.ad) |
| Sources servies par AJAX + nonce (WordPress) | URLs jamais dans le HTML | `admin-ajax.php` : action get_nonce puis get_source ; scripts inline **base64 data-URI** à décoder (J1F_POST_ID, j1fEpsData) |
| Mur d'inscription avant les lecteurs (dulourd.hair) | « S'inscrire pour regarder » + reCAPTCHA | Écarter — arnaque au paiement, aucun flux réel accessible |
| Landing page SEO sans catalogue (nakios.homes/biz) | page unique, liens vers un autre player | Écarter — vérifier qu'il existe un vrai catalogue interne |
| **Ressource partagée téléchargée par toutes les cartes** (AnimeSite v2) | **gel de l'app puis fermeture (ANR)** à l'ouverture de l'accueil | Un sitemap/annuaire utilisé par les fiches = 1 téléchargement unique : verrou (Mutex) + double vérification + cache par session |
| Comptage parallèle massif sur mobile (AnimeSama v2) | séries longues « incomplètes » (saisons à 1 épisode) | Semaphore(3) + relances avec délai ; distinguer erreur réseau (null → retry) de page sans contenu (0 → ne pas insister) |
| VF absente des panneaux de la fiche (AnimeSama) | pas de piste DUB alors que le site a une VF | Sonder `{saison}/vf/` (la 1re saison d'abord, puis tout si ça répond) — les CTA de la home révèlent les langues disponibles |

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


### 4.14 UN CHAMP DE TROP VIDE TOUT LE CATALOGUE (Streamixx v2, leçon Jackson)

Le catalogue Streamixx était vide alors que l'API répondait 200 : le DTO
déclarait `pager: Map<String, String>` alors que le JSON réel mélange
booléens (`hasMore`), entiers (`perPage`) et chaînes → Jackson lève une
MismatchedInputException, `runCatching { parseJson<…> }.getOrNull()`
avale l'erreur, et la liste reste vide. **Règle : ne déclarer que les
champs réellement utilisés** (ignoreUnknown=true couvre le reste), et
vérifier chaque endpoint avec les types EXACTS du JSON avant d'écrire
les DTO. Même famille de piège : les URLs échappées `https:\/\/…`
dans un JSON brut cassent les regex `https://…` (HentaiHaven, player
Pornovore) — toujours `.replace("\\/", "/")` avant de matcher.

### 4.15 NSFW FRANÇAIS : TRIER LES FAUX SITES ET TOUT VÉRIFIER (v10.1)

La demande « NSFW en français comme adkami » a donné un triage complet
du paysage français :
- **Intégrés (vérifiés de bout en bout)** : trixhentai.com (WordPress
  VideoTube, MP4 directs dans le setup jwplayer, catégories paginées,
  recherche `/?s=`) et pornovore.fr (tube maison FR : cartes
  `bloc-video` → JSON-LD `embedUrl` → page player avec
  `window.player_args.push({…})` contenant des MP4 signés 720/468/360 ;
  recherche `/recherche/{q}` ; pagination `-page{n}` collée au slug).
- **hentai.adkami.com** : catalogue + recherche OK mais le player
  (« L'anime est licencié ou aucune vidéo ») est masqué aux IP
  datacenter — même les animes du domaine principal. Cookie `nsfw=true`
  sans effet. Écarté en v10.1 (cf. §4.9)… puis **intégré en v11** à la
  demande explicite de l'utilisateur, avec le décodeur reversé depuis
  main.min.js et la règle « best-effort » (cf. §4.19).
- **hentaivost.fr** : Cloudflare « Just a moment » (403) sur toutes les
  pages depuis un datacenter.
- **hentai-fap.fr / hentai-paradise.fr / maruchihentai.cc** : templates
  « kitsune » tout-client (JS packé/obfusqué, zéro lien serveur) ou
  lecteurs de scans (pas vidéo) → écartés. La vidéo réelle est sur
  video.hentai-manga.io (403 direct).
- **Adresses mortes ou hors sujet** : hentaihaven.fr (= jeu Hentai
  Heroes), hentaifr.net (site d'actualité), aki-h.com (EN/Thaï).

### 4.16 SOUS-TITRES : TOUJOURS TESTER LE PROXY ANNONCÉ (Streamixx v2)

Le bundle de la SPA construit des URLs de sous-titres `/api/caption?
url=…` — mais cette route 404 sur le site ET sur la passerelle (le
service worker qui la servait est désactivé par le site lui-même).
En revanche les URLs `…srt?Policy=…` du CDN CloudFront sont signées et
téléchargeables directement : CloudStream accepte le SRT tel quel.
Vérifier le format renvoyé par le proxy AVANT de l'utiliser.


### 4.17 ADKAMI : LE DÉCODEUR « YOUTUBE-MARQUEUR » (v11)

Le player adkami (www comme hentai) n'est jamais dans le HTML pour un
visiteur anonyme datacenter ; sur www.adkami.com il L'EST (validé :
`<iframe data-src="//www.dailymotion.com/embed/video/xt6r66…">`).
Le JS main.min.js révèle le décodeur des liens chiffrés :

```js
e = e.split("https://www.youtube.com/embed/")[1]   // le marqueur n'est
e = atob(e)                                        // PAS un vrai lien YT
for (let o of e) t += String.fromCharCode((175 ^ o.charCodeAt()) - key[i].charCodeAt())
```

- le token = `https://www.youtube.com/embed/{base64}` ;
- base64 → octets ; `out[i] = ((175 XOR b) - key[i]) & 0xFFFF`, clé
  `ETEfazefzeaZa13MnZEe`, index cyclique (`i > len-2 ? 0 : i+1`) ;
- le résultat est l'URL réelle de l'embed (dailymotion, streamtape…).
⚠ CE N'EST PAS un simple XOR octet-clé : la formule `(175^b)-k` avec
soustraction. Toujours recopier la formule MINIFIED octet par octet,
puis la re-vérifier par un round-trip python (encoder ← décoder).

### 4.18 LARAVEL LIVEWIRE : LE SNAPSHOT QUI S'ÉCHAPPE (Movix v11)

Les sites Laravel+Livewire (movix.zip) rendent les composants serveur :
`wire:snapshot="&quot;…&quot;"` (JSON **HTML-échappé**). Pour changer de
saison : POST `/livewire/update` avec
`{"components":[{"snapshot":…,"updates":{},"calls":[{"method":"updateSeason","params":["2396"]}]}]}`.
La réponse = `{"components":[{"effects":{"html":"…"}}]}`.
⚠ `json = body` (param nicehttp) pour un corps JSON — `data=` c'est du
form-encodé. ⚠ La RECHERCHE du modal Livewire renvoie des cartes sans
liens individuels : vérifier s'il n'existe pas une simple page
`/search/{q}` (c'est le cas chez movix — 21 cartes, mêmes templates que
les listes) avant de coder un POST Livewire.

### 4.19 HENTAISTREAM.IO : 3× ROT13→BASE64 + FORM URL-ENCODÉ STRICT (v11)

Chaîne WordPress complète, crackée sans navigateur :
1. épisode → iframe `player.php?data={b64}` ;
2. page player → meta `x-secure-token="sha512-{token}"` ;
3. token : strip `sha512-` puis **3 fois {ROT13 → base64-décoder}** →
   JSON `{en, iv, uri}` ;
4. POST `{uri}api.php` (action=zarat_get_data_player_ajax, a=en, b=iv)
   → `data.sources[].src` = m3u8 (octopus).
⚠ Le POST DOIT être form-encodé avec les `+` du base64 en `%2B`
(curl --data brut → 500 côté serveur). En Kotlin : `data = mapOf(...)`
de app.post le fait naturellement ; en curl : `--data-urlencode`.
⚠ `M3u8Helper.generateM3u8(...)` est une fonction de l'OBJET COMPANION
(après vérification javap) — `M3u8Helper().generateM3u8` ne compile pas.

### 4.20 VIDZY : DEUX SYNTAXES D'APPEL DU MÊME XOR (Movix v11)

Le player vidzy encode ses sources `sources:[{src: (function(s){…})("TOKEN")}]`
— l'appel IIFE se termine par `})("…")` et non `}("…")` (FrenchStream) :
regex `\}?\("([A-Za-z0-9+/=]{40,})"\)` pour couvrir les deux. Le XOR
lui-même : base64 → reverse → `b[i] ^ ((0x3d + i*89 + sommeCodesHostname) & 0xFF)`.
Le hostname compte (vidzy.org ≠ vidzy.live ≠ fsvid.lol). Le fallback du
site (`/troll/master.m3u8`) est une vidéo piège : ne l'utiliser JAMAIS,
si le décodage échoue → pas de lien.
⚠ Sur un agrégateur multi-hébergeurs (movix : dood, voe, filemoon,
uqload, multiup, vidzy, flixeo…) : `loadExtractor` D'ABORD (extracteurs
officiels), `genericExtract` SEULEMENT en fallback — et le callback de
loadExtractor n'est PAS une coroutine : `newExtractorLink` (suspend) y
est interdit, construire `ExtractorLink(...)` directement.


### 4.21 UN WORKER QUI S'ÉTEINT AU HASARD : CASCADER LES SOURCES (Streamixx v4)

Le symptôme « catalogue OK, fiche OK, zéro serveur » venait de TROIS causes
empilées : (1) l'API avait migré vers `processedSources` (directUrl/streamUrl)
et l'extension ne lisait que l'ancien `downloads` ; (2) le worker workers.dev
répond par intermittence (reset/000, 429 rate-limit par IP — mortel derrière
un CGNAT) ; (3) le regex de redécouverte `[a-z0-9-]+\.workers\.dev` ne
pouvait JAMAIS matcher `mbx-core-gateway-v2.mymovieroom.workers.dev`
(sous-domaine à points). Règles : lire le normalisateur du site (fonction
`Gg()` du bundle : processedSources d'abord, downloads ensuite) ; cascader
les endpoints (direct → proxy → redécouvert → défaut) avec retry ; regex de
domaine TOUJOURS `[a-zA-Z0-9.-]+\.workers\.dev`.

### 4.22 NPE SILENCIEUX : NE RECONSTRUISEZ JAMAIS UN EXTRACTORLINK (Movix v2)

Renommer un lien d'extracteur (« Premium 1 · VF ») en reconstruisant
`ExtractorLink(label, l.name, l.url, l.referer, l.quality, l.headers,
l.extractorData, l.type, l.audioTracks)` → NPE garanti dès qu'un champ est
null chez l'extracteur (le constructeur compile des
`Intrinsics.checkNotNullParameter` sur headers/type/audioTracks — vérifiable
au javap) → avalé par `runCatching` → **0 serveur sans crash**. Le callback
de `loadExtractor` n'est PAS une coroutine (`newExtractorLink` y est
interdit) : la seule relance sûre = `callback(l)` direct. Le renommage
cosmétique ne vaut jamais la panne.

### 4.23 RÉSEAU « HENTAI PARADISE » : SECURE_LINK + CSRF + PONT DE SLUGS (v11.1)

Trois sites, un catalogue : hentaivost.fr (WordPress Cactus, Cloudflare),
hentai-paradise.fr (portail — sa section streaming **redirige** vers
hentai-fap.fr), hentai-fap.fr (kitsune, accessible). Chaîne vidéo validée :
`div#stream_ep` (data-v fichier, data-f dossier, data-type clip?) →
GET `/ajax/csrfToken.php` (X-Requested-With) → POST `/ajax/getlink.php`
(`file=/movies/{f}/{v}` + X-CSRF-Token) → chemin **signé nginx**
(`?st=…&e=…`) → `{server}{chemin}` = MP4 (206). Les slugs étant IDENTIQUES
entre les miroirs, un provider CF-bloqué peut « emprunter » le lecteur de
l'autre (`hentai-fap.fr/hentai-video/{slug}`). Pour la recon d'un site
 derrière Cloudflare sans navigateur : **Wayback** (homepage + listes
archivées, `web.archive.org/web/2026/https://…`), structure = cartes Cactus
`<div class="picture-content">` + recherche WP `/?s=`.
### 4.24 QUAND LES SOURCES DU SITE SONT MURÉES : DIAGNOSTIQUER PUIS COMPLÉTER (Movix v3 + Hentai-Fap v2 + Hentai-VOSTFR v2)

**Symptômes apparemment identiques, causes radicalement différentes.**

1. **Movix « 0 serveur » alors que la page contient `const videos`** : les 4 serveurs de GoT
   S1E1 étaient réels mais *murés individuellement* —
   - `vidzy.org` : page « Chargement vidéo » avec un **compte à rebours de 180 s** puis
     `location.reload()` (anti-bot *pour tout le monde*, pas seulement les datacenters).
     → détection (`"Chargement vidéo" in page && "location.reload" in page`) et abandon
     immédiat : ne faites jamais attendre l'utilisateur 3 minutes.
   - `uqload.net` : 403 depuis certaines IP (anti-bot datacenter) — sur IP mobile ça peut
     marcher via l'extracteur officiel, on laisse `loadExtractor` essayer.
   - `multiup.us/e/…` : multi-hébergeurs de *téléchargement* (uptobox…), pas de flux
     vidéo → ignoré.
   - `flixeo.xyz` : instable (timeouts) → extraction générique best-effort.
2. **Le correctif qui donne des serveurs *garantis* : un agrégateur en supplément.**
   - Titre → id **IMDb sans clé** : `https://v2.sg.media-imdb.com/suggestion/{1re
     lettre}/{requête}.json` → premier résultat `tt…` avec `qid` du bon type
     (`tvSeries`/`movie`). Le slug URL (remplacer `-` par espace) suffit comme requête.
   - **vidsrc.buzz accepte les ids IMDb directement** :
     `/embed/tv/tt0944947/1/1` ≡ `/embed/tv/1399/1/1` — pas besoin de convertir vers TMDB.
   - Chaîne : page embed → `var Q = {type,id,s,e,t}` → `GET /pl/api.php?a=sources&…` →
     serveurs `[{ref,name}]` → `GET /pl/api.php?a=play&ref&t` → `{url:"/_stream?id=…"}`
     = **HLS proxysé direct** (`#EXTM3U` vérifié).
   - Les `a=play` renvoient parfois **502** (limite de débit) → 1 retry avec délai,
     et tolérez qu'une partie des serveurs échoue (2-3 sur 4 suffisent).
3. **Hentai-Fap catalogue vide alors que curl voit les cartes** : le HTML réel contient des
   **sauts de ligne entre les attributs** (`class='vignFloatH tl'\n   href=…`). Une regex
   `class='…' href='…'` (une seule espace) matche en local sur une variante mise en cache
   puis ne matche plus sur l'appareil. → **Toujours `[\s\S]{0,N}?` entre les attributs**,
   jamais `' '` littéral.
4. **Hentai-VOSTFR timeout** : quand le site principal est derrière un défi Cloudflare
   insoluble même par CloudflareKiller, **basculez le moteur** : même réseau → même
   catalogue → mêmes slugs → catalogue/recherche/fiches/lecture servis par le pont,
   le site muré n'est plus qu'un secours. Réduisez aussi le nombre de sections (chaque
   section = une requête qui peut attendre le défi).


### 4.25 ENCODAGE DE CHEMIN vs FORMULAIRE + MATCHING IMDB STRICT + PARALLÈLE BORNÉ (v11.3)

1. **`URLEncoder.encode` produit `+` pour les espaces** (encodage de formulaire). Dans un
   *chemin* d'URL (`/search/{q}`), beaucoup de sites attendent `%20` — une recherche
   multi-mots peut renvoyer 0 résultat sans aucune erreur visible. → Toujours
   `.replace("+", "%20")` après `URLEncoder` quand la requête va dans un chemin.
2. **L'API de suggestion IMDb (`v2.sg.media-imdb.com/suggestion/…`) est floue ET littérale** :
   - elle renvoie des titres « proches » classés par popularité (« The Final Game of Death »
     → « The Death of Robin Hood » en premier) → **vérifiez le titre** après normalisation
     (casse/ponctuation/accents) avant d'utiliser l'id, sous peine de servir un autre contenu ;
   - elle matche mal la ponctuation de la requête (« Fifty / Fifty » échoue, « fifty fifty »
     matche) → envoyez la requête **sans ponctuation**.
3. **Un hôte mort ne doit jamais bloquer toute la liste** : vidzy.org affiche une attente de
   180 s, flixeo timeout — interrogez les serveurs **en parallèle** (`coroutineScope` +
   `launch(Dispatchers.IO)`) et bornez chaque serveur (`withTimeoutOrNull(15_000)`).
   Les `AtomicBoolean` remplacent les `var found` partagés entre coroutines.
4. **La page d'accueil CloudStream charge toutes les sections en parallèle** : N sections =
   N requêtes simultanées vers le même domaine — les sites Cloudflare rate-limitent au-delà
   de quelques-unes et seules les premières sections s'affichent. → Réduisez le nombre de
   sections et **retentez une fois** après un délai (la 2e passe arrive quand le rate-limit
   transitoire est passé).


