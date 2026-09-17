# Journal des mises à jour

## v11.5 — 2026-09-17 (Movix v6 lecteurs publics, + Frembed, + Xalaflix — 26 sources)

### 🎬 MovixProvider v6 — plus jamais « aucun serveur »
- Contenu absent de movix.men (Green Lantern, Resident Evil, animes…) : chaque source a un catalogue différent → **8 lecteurs publics TMDB interrogés en parallèle** (Videasy, Frembed, VidFast, VidSrc.cc, VidSrc.wtf, 2Embed, 111Movies, VidNest) en plus des serveurs du site et de vidsrc.buzz.
- Testé : Resident Evil 2002 absent de movix.men ET vidsrc.buzz → servi par les lecteurs publics.

### 🆕 FrembedProvider — frembed.surf (méthode « vraie disponibilité »)
- Le domaine change souvent → **résolution automatique** : adresse ⚙ → config publique GitHub du site → liste de secours, chaque candidat validé sur le catalogue.
- Catalogue = les vraies disponibilités du site (600+ pages, pas de TMDB fantôme) ; recherche TMDB **filtrée par disponibilité réelle**.
- Serveurs : `links[]` de l'API officielle (Voe, Dood, Uqload réels, langue par lien) + supplément vidsrc.buzz.

### 🆕 XalaflixProvider — xalaflix.tax (l'architecture « Movix historique », vivante)
- Page d'annonce xalaflix.online → domaine courant résolu et validé automatiquement.
- Catalogue tendances/films/séries/Top IMDb + recherche ; saisons via Livewire ; serveurs `const videos` (vidzy XOR, kakaflix dood/voe, vidsrc.xyz…) en parallèle bornés 15 s.
- Supplément : id TMDB lu dans les liens du site → lecteurs publics + vidsrc.buzz.

### 📚 Méthodes adoptées (réseau d'extensions FR de référence)
- Résolution de domaine multi-niveaux (config GitHub publique + page d'annonce + validation).
- Catalogue « vraie disponibilité » (sonder l'API avant d'afficher un résultat de recherche).
- Lecteurs publics TMDB en cascade : chaque agrégateur a un catalogue différent, les cumuler couvre presque tout.

---

## v11.4 — 2026-09-17 (Movix v5 : le VRAI site movix.men, refonte complète)

### 🎬 MovixProvider v5 — refonte sur le vrai site
- **movix.zip était un clone périmé** (serveurs morts) : le vrai site 2026 est **movix.men** (movix.online est la page d'annonce officielle). Extension réécrite depuis zéro sur sa vraie architecture :
  - **Catalogue TMDB** (clé publique du site, `language=fr-FR`) : Films tendance · populaires · les mieux notés · prochainement · Séries tendance/populaires, paginé, + **recherche** multi (films & séries).
  - **Fiches** TMDB complètes : synopsis FR, affiche, note, année, genres, durée ; séries = toutes les saisons/épisodes (titres FR, vignettes, résumé) chargées en parallèle.
  - **Serveurs** : l'API officielle `api.movix.men` sert **7 à 13 hébergeurs réels par contenu** (uqload.cx, voe.sx, filemoon.sx, vidmoly, vidoza, veev.to, lulustream, wishonly, darkibox, emmmmbed, mivalyo, listeamed…) — interrogés **en parallèle**, chacun borné à 15 s.
  - Contenu absent du site (« Contenu non disponible », animes, séries récentes) → **supplément agrégateur vidsrc.buzz** (HLS proxysés) via l'id TMDB directement.
- Le contenu érotique populaire est bien servi (Emmanuelle 2024 : 13 serveurs · Cinquante nuances : 12) ; le très obscur (non uploadé) passe sur l'agrégateur.

---

## v11.3 — 2026-09-16 (Movix v4, 18+ v3, agrégateur généralisé, Streamixx retiré)

### 🔧 MovixProvider v4 — recherche réparée + chargement accéléré
- **Recherche réparée** : `URLEncoder` encode les espaces en `+` (encodage de *formulaire*) alors que movix.zip attend `%20` (encodage de *chemin*) → toute recherche multi-mots renvoyait 0 résultat.
- **Correspondance IMDb stricte** : l'API de suggestion renvoie des titres « proches » (« The Final Game of Death » → « The Death of Robin Hood ») → le titre doit correspondre après normalisation, sinon l'agrégateur est ignoré (jamais de mauvaise vidéo). La requête est envoyée sans ponctuation (« Fifty / Fifty » échoue, « fifty fifty » matche).
- **Chargement des serveurs accéléré** : les serveurs du site sont désormais interrogés **en parallèle**, chacun borné à 15 s (vidzy.org = attente 180 s, flixeo = timeouts) ; les serveurs de l'agrégateur vidsrc.buzz sont aussi interrogés en parallèle.

### 🔧 Hentai-Fap v3 & Hentai-VOSTFR v3 — sections qui ne chargent pas
- **Cause** : la page d'accueil charge toutes les sections **en parallèle** vers le même domaine Cloudflare → rate-limit → seules les premières s'affichent. Sections réduites (Fap : 3 · Vost : 2, complémentaires) + **2e tentative automatique** après 1,5 s.
- Lecture : referer du MP4 = fiche (chaîne validée) + une 2e tentative sur `getlink.php`.

### 🆕 Supplément agrégateur généralisé (HLS multi-serveurs)
- **Purstream v6** et **FrenchStream v6** (films, via titre → IMDb) reçoivent le supplément **vidsrc.buzz** — déjà présent dans WaveWatch, CineStream, Zenix, Afterdark, 1JOUR1FILM et Movix.

### ❌ Streamixx retiré
- Supprimé à la demande de l'utilisateur (serveurs instables malgré 4 versions) — le dépôt passe à **24 extensions**.

---

## v11.2 — 2026-09-16 (Movix v3 agrégateur, Hentai-Fap v2, Hentai-VOSTFR v2)

### 🔧 MovixProvider v3 — serveurs supplémentaires garantis (séries ET films)
- **Diagnostic** : les serveurs propres au site sont souvent murés — vidzy.org affiche une page d'attente de 180 s, uqload.net renvoie 403, multiup ne liste que des hébergeurs de téléchargement, flixeo est instable. Le « 0 serveur » venait des sources, pas de l'extension.
- **Supplément agrégateur** : après les serveurs du site, l'extension résout le titre en id IMDb (API de suggestion publique, sans clé) puis interroge **vidsrc.buzz** (embed → `var Q` → `a=sources` → `a=play`) → **jusqu'à 4 serveurs HLS proxysés** supplémentaires (VNE, English, VEM, XPM…). Vérifié : Game of Thrones S1E1 et films → flux `#EXTM3U` valides.
- Page d'attente vidzy.org (180 s) détectée et ignorée instantanément ; retries sur les réponses 502 de l'agrégateur.

### 🔧 HentaiFapProvider v2 — catalogue vide réparé
- **Cause trouvée** : le HTML des cartes contient des **sauts de ligne entre les attributs** (`class='vignFloatH tl'` puis retour à la ligne puis `href=`) — la regex exigeait une espace unique → 0 carte sur l'appareil. Tolérance `[\s\S]` appliquée (12 cartes/page validées).
- En-têtes XHR jQuery (`Accept: application/json…`) ajoutés sur `csrfToken.php`/`getlink.php` (403 sans eux sur certaines IP).

### 🔧 HentaiVostProvider v2 — timeout réparé (moteur pont)
- hentaivost.fr reste derrière un défi Cloudflare que CloudflareKiller ne résout pas sur l'appareil → **catalogue, recherche, fiches et lecture servis par le pont hentai-fap.fr** (réseau Hentai Paradise identique : mêmes slugs, mêmes lecteurs, **sans défi**). hentaivost.fr n'est plus qu'un secours pour la fiche.
- Sections : Nouveautés VOSTFR · Non censuré · Harem · Inceste ; même correctif regex cartes que Hentai-Fap v2.

### 📚 TUTO
- §4.24 attente anti-bot 180 s vidzy.org · résolution titre→IMDb sans clé · ids IMDb acceptés par vidsrc.buzz · sauts de ligne entre attributs HTML · moteur-pont quand le site principal est muré.

---

## v11.1 — 2026-09-16 (réparation serveurs + Hentai-Fap + Hentai-VOSTFR)

### 🔧 StreamixxProvider v4 — « aucun serveur » réparé
- **Nouveau format de sources lu** : l'API sert désormais `processedSources` (URL directe + flux proxysé) en plus de `downloads` — l'extension lisait l'ancien format uniquement.
- **Passerelle instable prise en charge** : le worker répond parfois en erreur (reset/429) → l'essai cascade désormais **worker direct → proxy moviex → passerelle redécouverte → valeur par défaut**, avec 2 tentatives par endpoint.
- **Redécouverte réparée** : le regex accepte les passerelles à sous-domaines multiples (`a-b.c.workers.dev`) et scanne tous les bundles `index-*.js` du site.

### 🔧 MovixProvider v2 — « aucun serveur » réparé
- **Cause trouvée** : le lien renommé reconstruisait un objet `ExtractorLink` dont certains champs sont nuls chez les extracteurs officiels (dood, voe, filemoon…) → exception silencieuse → 0 serveur. Le lien est désormais relayé tel quel.
- CloudflareKiller ajouté sur toutes les requêtes (pages + embeds) ; lecture du tableau `videos` accepte `const`/`var`/`let` + fallback sur les iframes visibles si le tableau manque.

### 🆕 Hentai-Fap (hentai-fap.fr) — la section streaming de hentai-paradise.fr
- `hentai-paradise.fr` redirige sa section streaming vers `hentai-fap.fr` : c'est ce site qui est intégré (chaîne vérifiée de bout en bout : **206 MP4**).
- **VOSTFR · VOSTA · RAW · Non censuré · VOSTES** (2 700+ vidéos, 226 pages) + recherche.
- Lecteur craqué : jeton CSRF → `getlink.php` → **MP4 direct signé** (nginx secure_link).
- ⚙ changement d'URL (réseau Hentai Paradise).

### 🆕 Hentai-VOSTFR (hentaivost.fr)
- Le site est derrière Cloudflare : structure vérifiée via les archives publiques, et le défi est résolu sur l'appareil par CloudflareKiller intégré.
- **Nouveautés, Tous, Reupload + genres** (harem, famille, vanilla, ahegao, futanari…) + recherche WordPress.
- Lecture via le lecteur du réseau Hentai Paradise (même catalogue, mêmes slugs — chaîne validée) ; ⚙ double adresse (site + lecteurs).

### 📚 TUTO
- §4.21 worker instable + formats de sources en cascade (Streamixx v4) · §4.22 NPE silencieux du constructeur ExtractorLink (Movix v2) · §4.23 réseau Hentai Paradise (csrfToken + secure_link + pont de slugs + recon Wayback sous Cloudflare).

---
## v11 — 2026-09-16 (Streamixx v3, Movix, Adkami Hentai, HentaiStream, ⚙ URL partout)

### 🔧 StreamixxProvider v3 — « fiche introuvable » et « recherche absente » réparés
- **CloudflareKiller sur toutes les requêtes** (pages ET passerelle API) : les fiches ne disparaissent plus quand le site arme son bouclier.
- **Passerelle API redécouverte automatiquement** : si le worker change, l'extension le retrouve dans le bundle JS du site (cache 24 h) puis **retente** la requête — plus de « fiche introuvable » après un changement de domaine côté site.
- **Recherche réparée** : espaces encodés en `%20` (l'ancienne requête avec `+` renvoyait zéro résultat).

### 🆕 MovixProvider v1 — films & séries VF/VOSTFR (site demandé)
- movix.zip : **Tendances, Films, Séries, Top IMDb** (paginés) + recherche complète.
- Multi-serveurs par titre avec **versions** (TRUEFRENCH · VF · VOSTFR) : vidzy (HLS XOR décrypté), dood, voe, filemoon, uqload, multiup… via les extracteurs intégrés + extraction générique.
- Séries : toutes les saisons via l'API Livewire du site, épisodes paginés correctement.
- **⚙ Réglage d'URL** (bouton ⚙) si le domaine change.

### 🆕 AdkamiHentaiProvider v1 — hentai VOSTFR/raw (demande « re-analyser adkami »)
- hentai.adkami.com : **Nouveautés, Catalogue complet** (200/page) + recherche (API du site).
- Fiches : synopsis, genres, studio, note, épisodes VOSTFR/raw avec vignettes.
- Lecteur : décodeur reversé dans main.min.js (base64 + formule `(175^o)−clé`) appliqué aux iframes `data-src`/`data-litespeed-src`/`data-url`, puis extracteurs intégrés. ⚠ Le site masque les lecteurs aux visiteurs non connectés depuis certaines IP (« licencié ou aucune vidéo ») : si un épisode n'a pas de source côté site, aucune app ne peut l'inventer — catalogue et fiches restent utilisables.
- **⚙ Réglage d'URL** + cookie nsfw + CloudflareKiller.

### 🆕 HentaiStreamProvider v1 — hentai EN sous-titré (demande « plus de hentai »)
- hentaistream.io : **Derniers + 8 genres** (harem, bdsm, yuri, yaoi, ahegao…) + recherche paginée.
- Chaîne vidéo entièrement crackée : iframe player → jeton `sha512-` → **3× (ROT13 → base64)** → POST api.php (form-urlencodé strict) → **m3u8 multi-qualités**.
- Épisodes avec vignettes individuelles ; **⚙ Réglage d'URL** + CloudflareKiller.

### 🔁 Règle permanente appliquée : ⚙ changement d'URL sur CHAQUE extension
- **TrixHentaiProvider v2** et **PornovoreProvider v2** reçoivent le bouton ⚙ (adresse modifiable sans réinstaller, bouton « Par défaut » inclus), comme toutes les extensions récentes (Streamixx, AdkamiHentai, HentaiStream, Movix…).

### 📚 TUTO
- §4.17 adkami (décodeur marqueur-youtube) · §4.18 Livewire (snapshot échappé, updateSeason, piège du modal de recherche) · §4.19 hentaistream.io (ROT13×3 + form strict) · §4.20 vidzy (deux syntaxes d'appel, loadExtractor avant genericExtract).

---
## v10.1 — 2026-09-16 (correctif Streamixx + NSFW français)

### 🔧 StreamixxProvider v2 — catalogue réparé
- **Correctif catalogue** : un champ de parsing mal typé vidait toutes les sections (l'API répondait, mais le JSON était rejeté). Le catalogue s'affiche désormais normalement.
- **Sous-titres réparés** : les SRT signés du CDN sont désormais servis directement (l'ancien proxy renvoyait 404).
- **⚙ Réglage d'URL intégré** (bouton ⚙ sur l'extension) : adresse du site ET de la passerelle API modifiables — la passerelle est aussi **redécouverte automatiquement** dans le bundle JS du site si le worker change (cache 24 h, rattrapage auto en cas de panne).

### 🆕 NSFW français (TvType.NSFW — filtrées tant que « contenu adulte » est désactivé)
- **TrixHentai 18+** (trixhentai.com) : hentai & animes pour adultes **VOSTFR** — 12 sections (VOSTFR, non censuré, futanari, Naruto, One Piece, Dragon Ball, MHA, Pokémon, yuri, cosplay, IA…), **MP4 directs**, recherche.
- **Pornovore 18+** (pornovore.fr) : **films X français** (amateurs & stars du X françaises, scènes complètes 6-45 min) — 12 sections (Françaises, Beurettes, Amateurs, Asiatiques, Blacks, Latines, Lesbiennes, Stars du X…), **MP4 directs 360/468/720p**, recherche.
- Les extensions 18+ anglaises de la v10 (HentaiCity, HentaiHaven, Xvideos) restent installées.

### 🔍 Triage NSFW français (détaillé dans le TUTO §4.15)
- hentai.adkami.com : catalogue OK mais player masqué aux IP datacenter → écarté ; hentaivost.fr : Cloudflare ; hentai-fap/hentai-paradise/maruchi : JS obfusqué ou scans → écartés.

---

## v10 — 2026-09-16 (Streamixx, extensions 18+, francophonie)

### 🆕 StreamixxProvider v1 — films & séries MP4 directs + sous-titres
- **18ᵉ extension** (site fourni par l'utilisateur) : streamixx.xyz, catalogue servi par une passerelle publique (films, séries, animes sous-titrés **dont français**). **MP4 directs 360/480/720p** (signés) + **sous-titres** chargés via le proxy de légendes du site.
- Accueil : Tendances (paginé) + Sélection ; recherche complète ; séries avec toutes leurs saisons/épisodes (`seasons[].maxEp`).

### 🆕 Extensions 18+ (TvType.NSFW — filtrées tant que « contenu adulte » est désactivé dans CloudStream)
- **HentaiCity 18+** (hentaicity.com) : vidéos hentai/3D animées, **HLS directs** multi-qualités + MP4 de secours. Sections Récents / Populaires / Top notés / 3D / Anal, recherche fonctionnelle.
- **HentaiHaven Rule34 18+** (hentaihaven.xxx) : vidéos Rule34 animées via l'API WordPress headless du site (titres + miniatures) et **manifestes HLS en clair** (octopusmanifest.org) — 1080p/720p/480p + audio. Sections Dernières / Mises à jour, recherche WP.
- **Xvideos 18+** (xvideos.com) : vidéos amateurs & studios, **HLS directs** (480p/720p/1080p). Sections Nouveautés (paginé) / Meilleurs du mois, recherche.
- Ces trois sources ont été vérifiées de bout en bout (liste → carte → fiche → manifeste).

### 🆕 TeleFrance v3 — section 🌍 Francophonie
- Chaînes en français **hors France** (Afrique francophone, Belgique, Canada…) depuis la playlist langue française d'iptv-org, **sans doublon** avec la liste France (déduplication par nom) : Africa 24, Africanews FR, Bénin Web TV, Congo Planet TV, Digital Congo, Ivoire Channel, TV5… s'ajoutent aux 204 chaînes existantes + Madagascar.

### 📚 TUTO
- §4.10 passerelle SPA sans auth (Streamixx) · §4.11 extensions 18+ (TvType.NSFW, 3 sources vérifiées, impasses eporner/hanime/hqporner) · §4.12 union de playlists sans doublons · §4.13 les blogs « bons plans » = listes d'APKs (haloule, Bowd auth).

### 🔍 Triage des pistes fournies
- **haloule.com** : guide d'APKs (Netflix Mirror, MovieBox, FreeCine, AnimeTV…) — rien d'intégrable côté CloudStream (apps, pas des sites).
- **bowdtv.com** (TV+films+séries gratuit) : API derrière authentification + Turnstile → écarté.
- Streamixx : CDN vidéo 429 sur IP datacenter (comme FireStream v9) — fonctionne depuis une IP mobile.

---

## v9 — 2026-09-16 (vidsrc.buzz + hébergeurs craqués + TV Madagascar)

### 🆕 Agrégateur vidsrc.buzz — WaveWatch v7, Zenix v8, CineStream v5, UnJour1Film v6
- **Nouvelle source multi-serveurs TMDB** (films, séries ET **animes**) : la page d'embed `vidsrc.buzz/embed/{type}/{tmdb}` porte un jeton signé, l'API `/pl/api.php?a=sources` liste les serveurs, `a=play` rend l'URL du flux `/_stream?id=…` (HLS proxysé, direct). Chaîne entièrement reversée et testée (Breaking Bad S1E1 et Fight Club → m3u8 200 OK ; Redo of Healer 99071 → serveur détecté).
- **WaveWatch** : c'est la réponse au « aucun serveur sur les animes » (Redo of Healer & co) — la pile d'agrégateurs TMDB existante (wwembed, zeus, apiwiflix, playerix, movix, moviesApi, mouve, 1embed) est vide pour les animes ; vidsrc.buzz s'ajoute à la pile pour tout contenu TMDB.
- **Zenix, CineStream, 1jour1film** : même ajout — plus de serveurs sur les films ET les séries.

### 🛠️ UnJour1Film v6 — « peu de serveurs films / aucun sur les séries » réparé
- Diagnostic : les sources du site utilisent des hébergeurs que rien n'extrayait.
- **Byse (bysezoxexe.com) craqué** : l'API `/api/videos/{code}/` renvoie un `playback` chiffré **AES-256-GCM** ; la clé se dérive de `key_parts` selon la version (`parts[v-1] + parts[30-v]`, reverse du bundle JS lazy-loadé) → HLS direct déchiffré (vérifié : m3u8 lisible).
- **« C'était mieux avant » (cetaitmieuxavant.website)** : chaque page épisode embarque `videoData.servers` = 4 serveurs (FireStream, Byse, Lulustream, p2p) → tous relayés sauf p2p.
- **FireStream (firestream.site)** : `<script id="video-data">` avec `signedVideoUrl` (MP4/HLS direct). NB : le champ est masqué aux IP flaggées VPN — depuis un mobile il est là.
- Résultat : films = Lulustream + Byse + agrégateurs ; séries = jusqu'à 3 serveurs site + pile TMDB + vidsrc.buzz.

### 🆕 TeleFrance v2 — section 🇲🇬 Madagascar (chaînes d'Antananarivo)
- **TVM (Télévision Malagasy), RealTV, RTA (Radio Télévision Analamanga), Viva TV, KOLO TV, TV Plus Madagascar** : leurs directs YouTube sont résolus à la volée (page `/live` de la chaîne → vidéo en cours → extracteur YouTube intégré à CloudStream). Vérifié en direct : TVM « Vaovao » et RealTV « L'invité du jour » en flag `isLiveContent`.
- Hors direct, la page joue la dernière vidéo de la chaîne (utile pour les chaînes info). Logos inclus, recherche « madagascar » fonctionne.
- Les playlists IPTV publiques n'ont aucune chaîne malgache (mg.m3u = 1 chaîne hors-sujet) — les lives YouTube officiels des chaînes sont la seule source fiable.

### 📚 TUTO
- §4 : ligne vidsrc.buzz dans le tableau des agrégateurs.
- §4.7 : méthode complète « reverse d'un hébergeur chiffré AES-256-GCM » (bysezoxexe) + les cousins firestream/cetaitmieuxavant.
- §4.8 : TV malgache — résolution runtime des lives YouTube.
- §4.9 : leçon transversale — une IP datacenter n'est pas un téléphone (VPN-flagging, CF) : toujours garder `runCatching` + serveurs redondants.

### ✅ Révision des 14 sources
Afterdark, AnimeSama, AnimeSite, Flemmix, FrenchStream, Purstream, Vostfree, WaveWatch, 1jour1film : répondent OK depuis le sandbox. AnimoFlix et Franime : Cloudflare sur IP datacenter (CloudflareKiller en place — l'app sur mobile passe, comme vérifié v8 pour 1jour1film).

---

## v8 — 2026-09-15 (résolution Cloudflare partout + TV française en direct)

### 🆕 TeleFranceProvider v1 — Télé FR Direct
- **14ᵉ extension** : 200+ chaînes de télévision françaises **en direct** (généralistes, info, cinéma, séries, divertissement, animation, enfants, sport, documentaires, musique) via les flux publics IPTV du projet iptv-org.
- Chaînes géo-bloquées / non permanentes filtrées, logos inclus, **recherche par nom de chaîne**.
- Réglage ⚙ : n'importe quelle playlist M3U peut être branchée (par ex. Madagascar : `iptv-org.github.io/iptv/countries/mg.m3u`).

### 🛡️ Résolution Cloudflare sur TOUTES les sources (8 extensions)
- **UnJour1Film (conservé), AnimeSama, AnimeSite, CineStream, FrenchStream, Purstream, Vostfree** reçoivent l'intercepteur **CloudflareKiller** : si le défi Cloudflare se déclenche (selon le réseau/opérateur), CloudStream ouvre un mini-navigateur qui le résout — automatiquement pour les challenges JS, en un clic pour Turnstile — puis le cookie `cf_clearance` fait passer toutes les requêtes suivantes. AnimoFlix, Flemmix, Franime, WaveWatch et Zenix l'avaient déjà ; Afterdark passe par un proxy TMDB et n'en a pas besoin.
- C'est la réponse au « catalogue vide » de 1jour1film chez certains utilisateurs : le site est derrière Cloudflare et certains réseaux reçoivent un défi au lieu de la page.

---

## v7 — 2026-09-15 (correctifs des retours v6 : réglages, recherche, diagnostics)

### 🔧 UnJour1FilmProvider v4 — le bouton réglages apparaît enfin
- **Correction de câblage** : la fonction « changer l'adresse » existait depuis la v6 mais n'était pas branchée sur le bouton ⚙ (oubli d'`openSettings`) → le bouton n'apparaissait jamais. Corrigé.
- **Erreurs visibles** : si le site est inaccessible, l'accueil affiche désormais un message explicite (« changez l'adresse dans les réglages ⚙ ») au lieu de sections vides silencieuses — plus de devinettes.
- **En-têtes navigateur complets** (Accept, Sec-Fetch-…) : le WAF du site bloque les requêtes au user-agent non-navigateur (okhttp → 403) et peut noter les requêtes minimalistes.
- **Fallback REST étendu** aux « Dernières sorties » (en plus de Films/Séries).

### 🔧 PurstreamProvider v4 — même correction de câblage
- Le bouton ⚙ « adresse du site + API » apparaît désormais (oubli d'`openSettings` en v6).

### 🔧 AnimeSamaProvider v5 — recherche blindée
- Le POST de recherche peut être bloqué par certains réseaux/WAF alors que les GET passent : si le POST ne donne rien, **fallback GET `/catalogue/?search=…`** (recherche côté serveur du formulaire catalogue) + en-tête `X-Requested-With` pour mimer le site.
- **Correction de câblage** identique vérifiée (le réglage URL existait déjà et fonctionnait).

### 🔧 AnimeSiteProvider v4 — réglages d'URL ajoutés
- Bouton ⚙ pour changer l'adresse du site (demandé) + invalidation du cache sitemap si l'URL change.

---

## v6 — 2026-09-15 (retours utilisateur : saisons, épisodes, serveurs, réglages)

### 🔧 AnimeSamaProvider v4 — numérotation des saisons enfin lisible
- **One Piece n'affichait plus « 100+ saisons »** : les Kai étaient numérotés 101+, les hors-séries 41+, les films 90. Désormais **numérotation séquentielle** : les saisons/sagas normales gardent leur numéro (1-12), puis Films (13), Fan Letter/OAV (14), hors-séries (15-16) et Kai (17-27) continuent à la suite. Le libellé complet reste visible dans le nom de la saison et des épisodes (« Kai - Saga 1 (East Blue) · Épisode 5 »).

### 🔧 PurstreamProvider v3 — épisodes complets + réglages + serveurs en plus
- **Épisodes manquants des animes** : l'endpoint `media/{id}/seasons` est incomplet pour certains animes (Naruto : saison 1 seule, Naruto Shippuden : 32 épisodes sur 500). Les épisodes sont désormais construits par **UNION** de `media/{id}/sheet` (`urls`, numérotation continue — c'est celle qu'attend l'API stream) et de `seasons` (noms, vignettes, résumés). Naruto : **220 épisodes** (S1-S4), Shippuden : **500** (S1-S20).
- **Serveurs supplémentaires** : le champ `tmdbId` de la fiche alimente les lecteurs publics (Videasy, Frembed, Peachify, VidFast, VidSrc.cc, VidSrc.wtf, 2Embed, 111Movies, Braflix, VidKing, VidNest) et l'agrégateur apiwiflix (langues VF/VOSTFR), en plus des flux du site.
- **Réglages** : bouton ⚙ pour changer l'adresse du site **et** de l'API si le domaine change.
- ⚠ Limitation site : certains épisodes sont réellement absents chez Purstream (Jujutsu Kaisen S1E3-7, Demon Slayer S1E15 → 404 côté serveur) — les lecteurs publics TMDB peuvent alors les fournir en secours.

### 🔧 UnJour1FilmProvider v3 — catalogue blindé + serveurs en plus
- **Catalogue** : fallback **REST WordPress** (`/wp-json/wp/v2/movies|tvshows`, 8 423 titres) si l'ajax `j1f_catalogue` devient indisponible — le catalogue s'affiche dans tous les cas. (Le site a été re-testé avec le user-agent exact de CloudStream : l'ajax fonctionne ; si le catalogue restait vide, c'est que la **v1** était encore installée.)
- **Serveurs supplémentaires** : l'ID TMDB est extrait des scripts du site (`vp4-{tmdb}` pour les films, `var tmdb`/`var season` pour les saisons) → mêmes lecteurs publics + agrégateur apiwiflix que Purstream, en plus des sources Vidara/Lulustream du site.
- **Réglages** : bouton ⚙ pour changer l'adresse du site (rotation de domaine fréquente).

---

## v5 — 2026-09-15 (correctifs des retours v4)

### 🔧 AnimeSamaProvider v3
- **One Piece complet** : en 4G, les 27 requêtes de comptage simultanées échouaient pour la moitié des saisons (réduites à 1 épisode → ~220 visibles). Désormais : concurrence limitée à 3 + 2 relances par saison. Total réel : **1 369 épisodes** (sagas 1-12, films, OAV, hors-séries, Kai).
- **Piste DUB (VF)** : les fiches ne listent que les panneaux VOSTFR alors que les pages `/vf/` existent (découvert via les CTA de la home) — l'extension sonde la VF et remplit le sélecteur **SUB/DUB**.
- **Catalogue élargi** : section « Animes du planning » (~255 animes en cours) + routage des sections corrigé (`request.data`) — les 4 sections affichaient la même liste.

### 🔧 AnimeSiteProvider v3 — corrige le crash à l'ouverture
- Le sitemap (486 Ko) était téléchargé **20 fois en parallèle** (un par carte de l'accueil) → gel + fermeture de l'application (ANR). Désormais un seul téléchargement, protégé par verrou, mis en cache par session.

### 🔧 PurstreamProvider v2 — corrige « la fiche ne donne rien »
- Fiches sur de vraies URLs (`/movie/{id}`, `/serie/{id}`) au lieu du schéma interne `ps:{id}:{type}` + routage des sections corrigé.

### 🔧 UnJour1FilmProvider v2 — corrige « pas de catalogue »
- Le catalogue était interrogé avec le **libellé** de la section (« Films ») au lieu de sa clé (« movies ») → réponse vide. Corrigé.

> Leçon commune : ne JAMAIS router `getMainPage` sur `request.name` (libellé affiché) mais sur `request.data` (clé) — bug déjà rencontré en v3 et réintroduit par erreur dans les 3 nouveaux providers.

---

## v4 — 2026-09-15 : 13 extensions

### 🆕 PurstreamProvider (v1) — purstream.ad
- Site SPA React : API JSON ouverte `api.purstream.ad/api/v1/` découverte en reversant le bundle JS.
- Catalogue (~5 000 titres) paginé, carrousels par genre, recherche instantanée.
- **Flux HLS (m3u8) directs** — aucun lecteur intermédiaire. Épisodes avec vignettes TMDB.

### 🆕 UnJour1FilmProvider (v1) — 1jour1film0826b.website
- WordPress/DooPlay : catalogue AJAX `j1f_catalogue` (8 418 films + 1 269 séries), fiches TMDB.
- Sources servies par AJAX `j1f_get_source` / `j1f_get_ep_source` (nonce renouvelé à chaque session).
- Extraction locale des lecteurs **Vidara** (POST `/api/stream` → m3u8) et **Lulustream** (JS packé → m3u8).
- Épisodes avec vignettes TMDB (backdrop), sections Dernières sorties / Films / Séries.

### 🔧 AnimeSamaProvider v2
- **One Piece enfin complet** : la regex saison ne reconnaissait pas « Saga N » / « Kai - Saga N » / `saisonNhs` → toutes les sagas avaient `season = null` et CloudStream fusionnait les épisodes par numéro (il n'en restait que ~74). Numéros de saison désormais **uniques par panneau** (sagas 1-12, films 90, OAV 91, hors-séries 41+, Kai 101+, autres à la suite) + noms d'épisodes explicites (« Saga 1 (East Blue) · Épisode 5 »).
- **Sections VF / VOSTFR** : Nouveautés (carrousel), Derniers épisodes VOSTFR, Derniers épisodes VF, Catalogue complet — cartes avec posters et dédupliquées.
- Poster de la fiche appliqué aux épisodes.

### 🔧 AnimeSiteProvider v2 — corrige « installe avec erreur / extension invisible »
- Cause racine : l'annotation `@CloudstreamPlugin` était posée sur la classe provider au lieu d'une **classe `Plugin` séparée**. Le `.cs3` s'installait mais aucune extension n'était enregistrée. Classe `AnimeSitePlugin : Plugin()` ajoutée (pattern commun aux 12 autres extensions).
- Poster de la fiche appliqué aux épisodes.

### 🔧 FlemmixProvider v4 — la recherche wiflix fonctionne
- Le « bot shield » DLE exige le cookie JS **`h_check=25`** (posé par un script inline de la page d'accueil) : le POST de recherche le renvoie désormais → « batman » renvoie 84 résultats, 20 cartes par page avec posters.
- Bloc de recommandations masqué (`#no-results-rec`) correctement ignoré pour ne retourner que les vrais résultats.
- Nouvelles catégories gérées : `/film-ancien/`, `/saison-complete/`, `/vf/`, `/vostfr/`.
- Poster de la fiche appliqué aux épisodes des séries.

### 🔧 Divers
- **Auteur des extensions** : toutes les extensions sont maintenant signées **j97970293-lang** (13/13).
- **Posters sur les épisodes** (règle : si le site n'a pas de vignette d'épisode, on met l'affiche de la fiche) : Franime (vignette réelle si fournie, sinon affiche), Vostfree, Flemmix, FrenchStream, AnimeSama, AnimeSite. AnimoFlix / Zenix / Afterdark / WaveWatch / Purstream / 1JOUR1FILM avaient déjà des vignettes (TMDB/API).
- Versions : Afterdark 6, AnimoFlix 6, CineStream 3, Franime 3, FrenchStream 4, Vostfree 3, WaveWatch 6, Zenix 7 (rebuilds avec nouvel auteur).

### ❌ Sites re-testés et écartés
- **dulourd.hair** : lecteurs derrière un mur d'inscription (« S'inscrire pour regarder », reCAPTCHA) — arnaque classique, exclu.
- **movix.online** : simple vitrine qui redirige vers movix.men (déjà couvert par 3 agrégateurs).
- **Nakio / nakios.homes / nakios.biz** : pages d'atterrissage SEO sans catalogue propre (liens vers revestream.cc, SPA « accès réservé »).

---

## v3 — 2026-09-14 (après-midi) : 11 extensions, catalogues & recherches

### Correctif « catalogues identiques » (5 extensions)
Les sections routaient sur `request.name` (libellé) au lieu de `request.data` (clé) → chaque section affichait la même liste. Corrigé dans FrenchStream 3, Flemmix 3, Vostfree 2, CineStream 2, Franime 2.

### Recherches corrigées
- **FrenchStream** : la recherche GET renvoyait toujours le catalogue par défaut → bascule en POST DLE (« batman » → 18 fiches Batman).
- **Franime** : l'API Kitsu cherche en anglais/romaji → la recherche passe par le catalogue FRAnime lui-même (titres français) avec repli Kitsu.
- **Flemmix** : fiches corrompues (meta description = nom de fichier du poster, épisode 0 fantôme) → poster `#posterimg`, année « Date de sortie », `ep00` ignoré.

### 🆕 AnimeSiteProvider (v1) — animesite.fr
API cachée `/api/medias` + conversion des IDs internes en ID TVDB (× 336) + sitemap pour les slugs + lecteurs SibNet en MP4 directs.

### Enquête wiflix
- wiflix.voto = domaine parqué ; le vrai « wiflix » est **flemmix.cloud** (le site s'affiche « wiflix » dans ses titres).

---

## v2 — 2026-09-14 (matin) : 10 extensions
- Ajout CineStreamProvider (v1), miroirs de secours, réparations d'URLs des sources.
- `request.data` introduit pour distinguer les sections.

---

## v1 — 2026-09-14 (nuit) : 9 extensions
- Première version publique : AnimoFlix, Zenix, WaveWatch, Afterdark, Franime, FrenchStream, Flemmix, Vostfree, Anime-Sama.
- Dépôt + CI (branche `builds`) + releases + miroirs x0.at.
