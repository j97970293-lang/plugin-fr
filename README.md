# Plugin fr · Dépôt d'extensions CloudStream (`.cs3`)

Dépôt d'extensions [CloudStream](https://github.com/recloudstream/cloudstream) en français — **onze extensions** :

| Extension | Site | Contenu |
|---|---|---|
| **AnimoFlixProvider** | [animoflix.to](https://animoflix.to/) | animes, films & OAV en VF / VOSTFR |
| **ZenixProvider** | [zenix.best](https://zenix.best/) (secours : zenix.lol) | films & séries en VF / VOSTFR |
| **WaveWatchProvider** | [wavewatch.top](https://wavewatch.top/) | films, séries, animes & **TV en direct** en VF / VOSTFR |
| **AfterdarkProvider** | [afd926.mom](https://afd926.mom/) (miroir de afterdark.best) | films & séries en **VOSTFR** (multi-serveurs) |
| **FranimeProvider** | [franime.fr](https://franime.fr/) | **animes en VF & VOSTFR** (saisons, films) — lecteurs Sibnet, VidMoly, FileMoon… |
| **FrenchStreamProvider** | [fs27.lol](https://fs27.lol/) (adresse courante de french-stream) | films, séries & animes en **VF / VFQ / VOSTFR** — Uqload, Vidzy, FileMoon (Vidara), FS Premium… |
| **FlemmixProvider** | [flemmix.cloud](https://flemmix.cloud/) | films & séries VF/VOSTFR — jusqu'à **16 lecteurs** par titre (Vidara, Uqload, VidMoly, Voe…) |
| **VostfreeProvider** | [vostfree.ws](https://vostfree.ws/) | **animes VF & VOSTFR** + films d'animation (Sibnet, Uqload, Dood, Voe, Opvid…) |
| **AnimeSamaProvider** | [anime-sama.to](https://anime-sama.to/) | **animes VF & VOSTFR** — catalogue complet, saisons, films & miroirs multiples |
| **CineStreamProvider** | [cinestream.info](https://cinestream.info/) | **films VF/VOSTFR** — 15 lecteurs par titre (Vidara, Voe, Uqload, Vidmoly, StreamWish, FileLions…) |
| **AnimeSiteProvider** | [animesite.fr](https://animesite.fr/) | **animes VF & VOSTFR** — lecteurs SibNet MP4 directs, +2 500 animés (recherche EN/romaji) |


## 🔧🔧 Correctif v3 du 14 septembre (après-midi) — cause racine des catalogues identiques + recherches + AnimeSite (11 extensions)

> Ré-analyse complète des sites de fond en comble (r.jina.ai + reverse des bundles JS) **avant** toute modification, comme demandé.

### Le vrai bug des « catalogues identiques » (5 extensions)

Les correctifs v2 étaient bons côté URLs, mais les 5 extensions routaient leurs sections sur `request.name` (le libellé affiché) au lieu de `request.data` (la clé) : le `when` tombait **toujours** dans le `else` → chaque section affichait la même chose (FrenchStream/Franime : les films ; Flemmix : le carrousel d'accueil). Corrigé dans **FrenchStream v3, Flemmix v3, Vostfree v2, CineStream v2, Franime v2** — les sections sont maintenant réellement distinctes (vérifié page 1 ≠ page 2 ≠ autre section).

### Recherches corrigées (« ça donne autre chose ou rien »)

| Extension | Problème trouvé | Correctif v3 |
|---|---|---|
| **FrenchStream v3** | la recherche **GET** de fs27 renvoie *toujours* le catalogue par défaut (page identique octet par octet pour toute requête !) → résultats hors sujet | bascule en **POST** DLE : « batman » → 18 fiches Batman, « spider » → 18 Spider-Man, requête inconnue → 0 résultat propre |
| **Franime v2** | l'API Kitsu cherche en anglais/romaji : « attaque des titans » → *Whisper of the Heart*, « les chevaliers du zodiaque » → *Aku no Hana* | la recherche passe par le **catalogue FRAnime lui-même** (titres français `fr_fr`, ~11 Mo téléchargés une fois par session, parsing en flux) avec repli Kitsu automatique |
| **Flemmix v3** | fiches « bizarres » : la meta description du site est littéralement le *nom de fichier du poster* (« stream-vf-….jpg 1h 40min »), og:image absent, année prise au hasard dans le HTML, et un bloc `ep00` créait un épisode 0 fantôme sans lecteurs | synopsis du bloc structuré (même commenté dans le HTML), poster `#posterimg`, année « Date de sortie », épisode 0 ignoré |
| **CineStream v2 / Vostfree v2** | (victimes du seul bug `request.name`) | routage corrigé — les recherches étaient déjà bonnes côté serveur (testées : accents inclus) |
| **Franime v2** | affiche sur chaque épisode | retirée (« comme avant ») |

### 🆕 AnimeSiteProvider (v1) — animesite.fr, 11ᵉ extension

Écarté le matin même comme « SPA sans API »… la ré-analyse de l'après-midi (r.jina.ai + reverse des chunks Next.js) a tout débloqué :
- **Catalogues** : API cachée `/api/medias?name={trending|added-episode|top-rated}&page=N` — Tendances, Nouveaux épisodes, Les mieux notés (paginés).
- **Recherche** : `/search/{q}` — résultats dans le payload RSC (render server components) décodé.
- **+2 500 animés** : conversion des IDs internes (× 336 = ID TVDB, trouvée dans le bundle) + sitemap.xml pour les slugs exacts.
- **Lecteurs** : `POST /api/stream/token` → page SibNet proxifiée → **MP4 directs** (VOSTFR + VF sondés automatiquement, liens externes/pub ignorés).
- **Fiches** : saisons & nombre d'épisodes via le JSON-LD de la fiche, synopsis/affiche/genres/année.

### Enquête « wiflix » & sites re-testés

- **wiflix.voto** = domaine **parqué** (annuaire de pubs) — le vrai « wiflix » que vous voyez est **flemmix.cloud** (le site s'affiche lui-même « wiflix » dans ses titres !) → c'est FlemmixProvider, corrigé en v3.
- Re-testés via r.jina.ai : **dulourd.hair** (bouton « Regarder » → ferme à pub topnox, aucun lecteur réel : exclu), **purstream.ad** (coquille vide), **movix.online** (vitrine vers movix.men, déjà intégré), **andoks.cc / novastream.top** (coquilles vides), **moiflix.org** (redirige vers moiflix.fans, sans lecteurs ouverts) — aucun n'est exploitable.

## 🔧 Correctif du 14 septembre (midi) — catalogues répétitifs corrigés + CineStream (FrenchStream v2, Flemmix v2, CineStream v1)

| Extension | Correctif |
|---|---|
| **FrenchStream v2** | les sections « Films VF / Séries VF / Animes » renvoyaient toutes le même contenu que Films/Séries (URLs inexistantes sur le site) → **11 sections réelles** : genres films (Action, Comédie, Animation, Horreur, SF, Thriller), Séries, **Animes** (catégorie animation réelle), **K-Dramas**, Séries Netflix — pagination vérifiée |
| **Flemmix v2** | les 4 sections affichaient toutes la page d'accueil (le carrousel du haut est identique partout et les listings réels n'étaient pas parsés) → **12 sections** avec les listings `mov` réels (20/page) : genres, Films anciens, Séries, **VF**, Saisons complètes + accueil (carrousels) — pagination vérifiée page 1 ≠ page 2 |
| **CineStream v1** (NOUVEAU) | [cinestream.info](https://cinestream.info/) — films FR avec **15 lecteurs par titre** : Vidara, Voe, LuLuTV, Vidsonic, FMX, Hxfile, DdStream, Save, Uqload, Vmoly, Filelions, Swish, vostfr 1-3 — recherche SSR, 16 genres paginés, lecteurs via `/player/{tmdb}/{n}` → URL hébergeur directe |

> ℹ️ **Recherches vérifiées une par une** (14/09 midi) : FrenchStream ✓, Vostfree ✓, Anime-Sama ✓, AnimoFlix ✓, Zenix ✓, WaveWatch ✓, Afterdark ✓, CineStream ✓. Flemmix : le moteur de recherche du site est **neutralisé côté serveur** (bot shield) — la navigation par catalogues est la voie d'accès (aucune recherche possible, même dans un navigateur).
> ℹ️ Les épisodes n'affichent **pas de miniature** (numéro simple), comme demandé.

## 🆕🆕 Nouveauté du 14 septembre (matin) — 4 nouvelles extensions + plus de serveurs (French Stream, Flemmix, Vostfree, Anime-Sama · Afterdark v5, WaveWatch v5, Zenix v6)

### 4 nouvelles extensions

| Extension | Détail |
|---|---|
| **FrenchStreamProvider** (v1) | **french-stream** via son adresse courante `fs27.lol` : films via `film_api`, séries & animes via `static/series` — **VOSTFR, VF, VFQ et VO** distingués, jusqu'à ~10 lecteurs par titre (**Uqload, Vidzy, FileMoon/Vidara, FS Premium (fsvid), Kokoflix, Netu/Kakaflix, Dood, Voe…**), extraits par notre extracteur Vidara éprouvé + décodage XOR vidzy/fsvid + docteur de liens ; recherche DLE complète |
| **FlemmixProvider** (v1) | **flemmix.cloud** : films avec **jusqu'à 16 lecteurs inline** (Vidara, Voe, LuLuTV, Uqload…), séries avec épisodes **VOSTFR (vs) + VF (vf)** ; catalogue paginé (30 titres/page) ; la recherche du site est neutralisée par son « bot shield » → navigation par catalogues (films/séries/animes), le bouton Réglages permet de changer l'adresse |
| **VostfreeProvider** (v1) | **vostfree.ws** (DLE Animix) : 1 fiche = un anime **complet** (jusqu'à 346 épisodes détectés sur One Piece) avec tous ses lecteurs (**Sibnet, Uqload, Mytv/Myvi, Dood, Voe, Opvid, VidMoly, CloudVideo…**) ; recherche opérationnelle (« naruto » → 6 résultats VF/VOSTFR) ; VF signalée dans le titre |
| **AnimeSamaProvider** (v1) | **anime-sama.to** : catalogue + recherche (fetch.php), saisons groupées par nom (« Saga 1 (East Blue) »…), épisodes via `episodes.js` avec **miroirs multiples par épisode** (eps1/eps2…), films d'animes inclus ; lecteurs AnsEmbed via JwPlayerHelper (déjà éprouvés dans AnimoFlix) |

### 3 extensions enrichies (encore plus de serveurs)

| Extension | Nouveaux serveurs |
|---|---|
| **Afterdark v5** | **MoviesApi** (API interne `/api/vidora` — films & épisodes, best-effort silencieux), **VidNest** (extracteur intégré CloudStream), et le fallback **movix.men** en plus de `api.movix.cash` (les deux domaines sont essayés) |
| **WaveWatch v5** | idem : MoviesApi + VidNest + fallback movix.men (movix & french-stream) |
| **Zenix v6** | idem : MoviesApi + VidNest + fallback movix.men (movix & french-stream) |

### 📥 Installation & miroirs

- **Dépôt CloudStream** (recommandé — 10 extensions) : `https://raw.githubusercontent.com/j97970293-lang/plugin-fr/builds/repo.json`
- **Miroir x0.at** : `https://x0.at/YwJi.json`
- **Release la plus récente** : [fix-v1-2026-09-14 (Pre-release)](https://github.com/j97970293-lang/plugin-fr/releases/tag/fix-v1-2026-09-14) — les 10 `.cs3` en pièces jointes
- **Fichiers individuels (miroirs x0.at, dernières versions)** : CineStream `https://x0.at/Oybz.cs3` · FrenchStream v2 `https://x0.at/ljPN.cs3` · Flemmix v2 `https://x0.at/ItKX.cs3` · Vostfree `https://x0.at/oBeg.cs3` · Anime-Sama `https://x0.at/erhU.cs3` · Afterdark v5 `https://x0.at/m0B9.cs3` · WaveWatch v5 `https://x0.at/Gigc.cs3` · Zenix v6 `https://x0.at/bgCH.cs3` · plugins.json `https://x0.at/2h6o.json`

> ℹ️ **Pourquoi ces sites ?** Les 9 adresses proposées ont toutes été examinées : `purstream.ad` (lecteur verrouillé par veske.io), `dulourd.hair` (Turnstile obligatoire), `animesite.fr` (SPA « sans API »… jusqu'à la ré-analyse v3 qui a trouvé l'API cachée → intégré en v3), `1jour1film` (recherche bloquée, lecteurs déjà couverts par les agrégateurs existants) et `movix.online` (identique à movix.men déjà intégré) ont été écartées — tout le reste est intégré.

## 🆕 Nouveauté du 14 septembre — FRAnime v1 (animes VF/VOSTFR)

Nouvelle extension pour **[franime.fr](https://franime.fr/)** — le catalogue d'animes FR de référence :

| Caractéristique | Détail |
|---|---|
| **Catalogue & recherche** | listes **Tendances, En cours, Populaires, Mieux notés, Films** via l'API publique **Kitsu** (les mêmes fiches que FRanime) — le lourd catalogue du site (11 Mo) n'est jamais téléchargé |
| **VF + VOSTFR** | chaque épisode sonde automatiquement les lecteurs **VOSTFR puis VF** (Sibnet, VidMoly, SendVid, FileMoon, Uqload, Streamtape, Dood, VK…) |
| **Saisons & films** | séries multi-saisons avec miniatures par épisode, détection automatique des films |
| **Anti-blocage** | challenge **Cloudflare** résolu sur l'appareil + décodage du jeton `watch2` du lecteur (chiffré) + détection des lecteurs leurres |
| **Qualité** | liens étiquetés « Sibnet · VOSTFR », vérifiés jouables avant affichage (docteur de liens) |

> ℹ️ **FRanime n'indexe pas tous les animes de Kitsu** : si une fiche n'est pas sur le site, l'extension l'indique clairement (« pas disponible sur FRAnime »).


## 🚀 Mise à jour du 14 septembre (nuit) — plus de serveurs (AnimoFlix v5, Zenix v5, WaveWatch v4, Afterdark v4)

**Pourquoi si peu de serveurs ?** Les agrégateurs ont **rotaté leur catalogue d'hébergeurs** : les liens arrivent désormais sur de nouveaux lecteurs qu'aucun extracteur ne connaissait (miroirs Vidara, BlinkFlux, vidzy…), et certains agrégateurs ont perdu des titres (One Piece n'est plus chez wiflix/movix/1embed).

| Amélioration | Effet |
|---|---|
| **Vidara : 16 domaines miroirs** | vidaraw.com, vidaraa.cc, vidarax.cc, vidara.so, vidavaca.net… partagent la même API — tous gérés par notre extracteur fiable (vrai type HLS/MP4) au lieu de l'extracteur intégré bogué |
| **BlinkFlux** (zeus/mouve) | lecteur à charge chiffrée : déverrouillage automatique → flux MP4 direct — un serveur de plus sur films et séries |
| **Lecteurs videojs obfusqués** (vidzy.cc, fsvid.lol…) | source XOR décodée par l'extension → serveurs supplémentaires via le fallback générique |
| **AnimoFlix ~5× plus rapide** | lecteurs interrogés **en parallèle**, vérification de jouabilité non bloquante — la liste des serveurs apparaît **entière et vite** (fin des listes partielles « un seul serveur ») |
| **Docteur de liens assoupli** | 403 (géoblocage/anti-bot incertain), 416, 429 → lien **conservé** ; seuls les liens vraiment morts (404/5xx, pages HTML) sont masqués |
| **Formats reconnus élargis** | FLV, Ogg, MP3/AAC détectés jouables |

## 🛠️ Correctif du 14 septembre (soir) — erreur 3003 « Source error » (AnimoFlix v4, Zenix v4, WaveWatch v3, Afterdark v3)

| Extension | Nouveautés |
|---|---|
| **Toutes** | **Erreurs 3003 `PARSING_CONTAINER_UNSUPPORTED` éliminées** : chaque lien est maintenant **vérifié jouable avant d'être affiché** (mini-requête au flux : HLS/MP4/WebM/TS/DASH reconnus) — les liens morts, pages HTML et fichiers mal typés sont masqués au lieu de faire planter le lecteur ; en cas de doute réseau le lien est conservé |
| **Toutes** | **Serveur « Vidara » réparé** : l'extracteur intégré CloudStream marquait tous ses liens en HLS sans vérifier (MP4/lien mort → 3003) et plantait sur les sous-titres ; chaque extension embarque désormais son extracteur Vidara qui respecte le vrai type de fichier |
| **Toutes** | Doublons de liens fusionnés (même URL finale via plusieurs lecteurs = une seule entrée) |

## 🔄 Mises à jour du 14 septembre 2026

| Extension | Nouveautés |
|---|---|
| **Toutes** | Bouton **« Réglages »** sur la fiche de l'extension pour **changer l'adresse du site** (miroirs, déménagements) — sans réinstaller |
| Zenix / WaveWatch / Afterdark | **One Piece & animes longs réparés** : secours **AnimoFlix** intégré (derniers épisodes VOSTFR inclus, vérification anti-mauvais-épisode), + purge des liens « mauvais épisode » de playerix (TV) et mouve (TV) |
| AnimoFlix | Miniatures des épisodes **comme avant** : le numéro d'épisode est de nouveau visible |

📚 **[TUTO.md](TUTO.md)** — guide complet (écrit pour être lisible par une IA) : créer une extension CloudStream, analyser un site de streaming, la stack d'agrégateurs, tous les pièges rencontrés.

---

# AnimoFlix · Extension CloudStream (`.cs3`)

Extension pour **[animoflix.to](https://animoflix.to/)** — animes, films & OAV en **VF / VOSTFR**.

## ✨ Fonctionnalités

- **Page d'accueil** : 14 sections réelles du site (Tendances, Top IMDb, Films, Séries + 10 genres : action, aventure, animation, comédie, SF, horreur, thriller, romance, policier, drame) — la pagination des listes est chargée en JS sur le site, chaque section affiche sa première page
- **Recherche** : API JSON du site (`/ajax/search/suggest`) avec titre, affiche, année — gère accents, apostrophes et majuscules (la route HTML `/search/{requête}` ne filtre pas côté serveur : elle renvoie toujours les derniers ajouts, elle n'est donc plus utilisée)
- **Fiches** : titre, affiche, note, année, synopsis, genres — extraits des badges de la fiche (les badges « vues » et IMDb sont ignorés)
- **Séries** : toutes les saisons et épisodes parsés depuis les liens `/episode/{slug}/{saison}-{épisode}`, avec titre et miniature d'épisode (celle de la fiche si absente) ; ouvrir un lien épisode remonte automatiquement à la série
- **Lecture — v2 : 6 agrégats de sources en parallèle** (l'ID TMDB de la fiche sert de clé commune) :
  - **1embed.cc** — playlists HLS directes jouables immédiatement (extracteur maison : Solari, Necro…), émises en premier
  - **apiwiflix** (apis.wavewatch.top) — 6–15 liens hébergeurs **avec langue** (VF/VOSTFR), films & séries
  - **playerix** (apis.wavewatch.top) — jusqu'à 118 boutons « Serveur » par contenu : liens hébergeurs dédupliqués par hôte+langue **et parfois des playlists HLS directes** (jouées sans extraction) ; chaque bouton indique sa langue (🇫🇷 VF / 🇫🇷 VOSTFR / 🌐 MULTI)
  - **movix** (api.movix.cash) — ~12 liens FR par film/épisode (Uqload, LuluStream, Voe, Wish, DSVPlay…)
  - **french-stream** (api.movix.cash `/fstream`) — liens avec **vraies étiquettes VFQ / VF / VOSTFR** (films)
  - **PrimeSrc** — jusqu'à 14 serveurs (Filemoon, Dood…) — protégé Cloudflare : best effort
  - plus les **~24 boutons « Serveurs de lecture » de la page Zenix** (Frembed, WaveWatch, VidFast, VidLove, Mostream…), en dernier recours
- **Ordre intelligent** : les liens sont essayés **en parallèle** (6 à la fois) et triés — **VF d'abord**, puis VOSTFR, puis langues inconnues ; au sein de chaque langue, les agrégateurs les plus fiables d'abord
- **Langue affichée sur chaque lien** : « Filemoon · VF », « Vidzy · VOSTFR »… les langues viennent des agrégateurs et des libellés des boutons (`… | VF`)
- **Filtre anti-erreurs 3003** : images, pubs, YouTube, `.vtt`/`.srt`, miniatures et trailers sont exclus des résultats du fallback générique (og:video, `<source>`, `file:`, .m3u8/.mp4/.webm)

## 📥 Installation

### Méthode 0 — Test immédiat (hébergement public temporaire)

Avant même de pousser ce dépôt sur GitHub, vous pouvez tester l'extension en ajoutant ce `repo.json` public dans CloudStream (**Paramètres → Extensions → Ajouter un dépôt**) :

```
https://x0.at/gMeP.json
```

- `repo.json` (v2) : https://x0.at/gMeP.json
- `plugins.json` : https://x0.at/aVsv.json
- `AnimoFlixProvider.cs3` (v2) : https://x0.at/nrrJ.cs3

> ⚠️ Hébergement temporaire (expiration sous quelques semaines/mois). Pour un lien pérenne, poussez le dépôt sur GitHub — le workflow publiera tout automatiquement sur la branche `builds`.
> ℹ️ Si vous aviez ajouté l'ancienne URL de test (v1, `x0.at/RluP.json`) : supprimez d'abord ce dépôt et l'extension dans CloudStream, puis ajoutez la nouvelle ci-dessus.

**v5** : plus de serveurs — miroirs Vidara (16 domaines), BlinkFlux, lecteurs vidzy/fsvid décodés, lecteurs **en parallèle** (liste des serveurs instantanée, fin des listes tronquées), docteur assoupli (403/416/429 conservés).

**v4** : correctif erreurs 3003 — docteur de liens (chaque lien vérifié jouable avant affichage) + extracteur Vidara réparé + doublons fusionnés.

**v3** : bouton « Réglages » pour changer l'adresse du site ; miniatures des épisodes comme avant (numéro visible).

**v2** : tous les serveurs/lecteurs de la page épisode sont maintenant listés (y compris SendVid, AnsEmbed et Odysee via un extracteur dédié + un fallback générique mp4/m3u8), et les épisodes affichent la miniature de la fiche de l'anime.

### Méthode 1 — Ajouter le dépôt (recommandé)

Dans CloudStream : **Paramètres → Extensions → Ajouter un dépôt** et coller l'URL `repo.json` de la branche `builds` de ce dépôt :

```
https://raw.githubusercontent.com/j97970293-lang/plugin-fr/builds/repo.json
```

> Le workflow GitHub Actions construit et publie automatiquement `AnimoFlixProvider.cs3` + `plugins.json` + `repo.json` sur la branche `builds` à chaque push.

### Méthode 2 — Depuis la release GitHub

Téléchargez le `.cs3` attaché à la release [**v2 (Pre-release)**](https://github.com/j97970293-lang/plugin-fr/releases/tag/v2) puis dans CloudStream : **Paramètres → Extensions → Installer un fichier**.

### Méthode 3 — Installer le fichier du dépôt

Téléchargez [`releases/AnimoFlixProvider.cs3`](releases/AnimoFlixProvider.cs3) puis dans CloudStream : **Paramètres → Extensions → Installer un fichier** (ou placez le fichier dans le dossier `Cloudstream3/extensions`).

> **Note** : cette extension est compilée contre l'API `pre-release` de CloudStream. Utilisez une version récente de l'app ([releases pre-release](https://github.com/recloudstream/cloudstream/releases)).

---

# Zenix · Extension CloudStream (`.cs3`)

Extension pour **[zenix.best](https://zenix.best/)** (domaine de secours officiel : `zenix.lol`) — films & séries en **VF / VOSTFR**.

## ✨ Fonctionnalités

- **Page d'accueil** : 14 sections réelles du site (Tendances, Top IMDb, Films, Séries + 10 genres : action, aventure, animation, comédie, SF, horreur, thriller, romance, policier, drame) — la pagination des listes est chargée en JS sur le site, chaque section affiche sa première page
- **Recherche** : API JSON du site (`/ajax/search/suggest`) avec titre, affiche, année — fallback HTML `/search/{requête}`
- **Fiches** : titre, affiche, note, année, synopsis, genres — extraits des badges de la fiche (les badges « vues » et IMDb sont ignorés)
- **Séries** : toutes les saisons et épisodes parsés depuis les liens `/episode/{slug}/{saison}-{épisode}`, avec titre et miniature d'épisode ; ouvrir un lien épisode remonte automatiquement à la série
- **Lecture — TOUS les serveurs listés** : chaque page film/épisode contient les ~24 serveurs du site (« Serveurs de lecture » + « Autres sources ») : Frembed, Peachify, VidFast, Mostream, HNEmbed, Catflix, WaveWatch, Videasy, PrimeSrc, VidLove, VidUp, Braflix, StreamIMDb, VidSrc PM/IO, AnyEmbed, Viduki, **1Embed**, VidKing, 2Embed, SuperFlix… Ils sont essayés **en parallèle** (6 à la fois), les **VF d'abord**, puis VOSTFR, puis le reste ; les lecteurs internes Zenix (BlinkFlux / « Lecteur Gratuit 4K », protégés par pubs/captcha) en dernier
- **Extracteur maison `1embed.cc`** : les pages de ce lecteur contiennent la liste de ses serveurs (Solari, Necro…) avec des playlists HLS directement jouables — testé et validé sur films **et** épisodes
- **Dernier recours** : l'ID TMDB de la fiche est utilisé pour reconstruire une URL 1embed si aucune source n'a répondu
- **Fallback générique** : pour tout lecteur sans extracteur, la page de l'embed est analysée (og:video, `<source>`, `file:`, .m3u8/.mp4/.webm)

## 📥 Installation

Mêmes méthodes que AnimoFlix (dépôt ci-dessus), ou directement :

- **`.cs3` v2** : release [**zenix-v2 (Pre-release)**](https://github.com/j97970293-lang/plugin-fr/releases/tag/zenix-v2) → **Paramètres → Extensions → Installer un fichier**
- Miroirs x0.at : `repo.json` → https://x0.at/M5wA.json · `plugins.json` → https://x0.at/k3u9.json · `ZenixProvider.cs3` v2 → https://x0.at/uVYD.cs3

**v5** : plus de serveurs — miroirs Vidara (16 domaines), BlinkFlux, lecteurs vidzy/fsvid décodés, lecteurs **en parallèle** (liste des serveurs instantanée, fin des listes tronquées), docteur assoupli (403/416/429 conservés).

**v4** : correctif erreurs 3003 — docteur de liens (chaque lien vérifié jouable avant affichage) + extracteur Vidara réparé + doublons fusionnés.

**v3** : bouton « Réglages » pour changer l'adresse du site ; secours anime AnimoFlix (One Piece & co, derniers épisodes inclus) ; en TV seuls les liens playerix fiables (HLS) sont gardés — les iframes renvoyaient le même épisode pour tous.

**v2** : correction de l'erreur 3003 « Source error » (filtre anti-faux-positifs), bien plus de sources (1embed HLS + apiwiflix + playerix + movix + french-stream + PrimeSrc + les 24 boutons du site, avec **HLS directs** playerix), recherche corrigée (API suggest uniquement — la route HTML ne filtrait pas) et **langue VF/VOSTFR affichée sur chaque lien**.

## 🔨 Compiler soi-même

```bash
./gradlew ZenixProvider:make    # → ZenixProvider/build/ZenixProvider.cs3
```

## ⚠️ Notes techniques

- Le site est un rendu serveur PHP **sans Cloudflare** — pas de WebView nécessaire.
- L'ID TMDB est extrait de l'URL du lecteur interne Zenix présente dans le HTML de la fiche (`[?&]tmdb=(\d+)`) ; il est requis pour les agrégateurs. Sans TMDB, seuls les boutons de la page sont utilisés.
- Paramètres playerix : l'API ignore `saison=` (français) — l'extension utilise `season=`/`episode=` (anglais), faute de quoi l'épisode 1×1 était servi pour toutes les demandes.
- Movix : les liens des séries sont imbriqués dans `current_episode.player_links` (films : `player_links` à la racine) ; `api.movix.llc` est mort, seul `api.movix.cash` répond.
- Certains hébergeurs tournent leurs domaines (rebeccapracticeloss.com → johnbeyondnation.com…) : ces liens tombent parfois en 404 — ils sont ignorés silencieusement, les autres sources compensent.
- Les lecteurs internes Zenix (BlinkFlux / « Lecteur Gratuit 4K ») sont protégés par pubs/captcha : jamais utilisés.
- Si le site change d'adresse, modifiez `mainUrl` en haut de `ZenixProvider.kt` (`https://zenix.lol`).

---

# WaveWatch · Extension CloudStream (`.cs3`)

> **v4** : plus de serveurs — miroirs Vidara (16 domaines), BlinkFlux, lecteurs vidzy/fsvid décodés, docteur assoupli (403/416/429 conservés).

> **v3** : correctif erreurs 3003 — docteur de liens (chaque lien vérifié jouable avant affichage) + extracteur Vidara réparé + doublons fusionnés.

> **v2** : bouton « Réglages » pour changer l'adresse du site ; secours anime AnimoFlix (One Piece & co, derniers épisodes inclus) ; en TV seuls les liens playerix/mouve fiables sont gardés (les autres renvoyaient le même épisode pour tous).

Extension pour **[wavewatch.top](https://wavewatch.top/)** — plateforme de streaming FR (catalogue TMDB) : films, séries, animes et **chaînes TV en direct**.

## ✨ Fonctionnalités

- **Catalogue 100 % API JSON publique** (FastAPI + TMDB en français) : tendances films/séries/animes, prochaines sorties, 6 genres de films et 3 de séries — fiches complètes (titre, affiche HD, fond, note, année, synopsis, genres, durée) et **toutes les saisons/épisodes** (titre, résumé, miniature, note, durée ; les saisons spéciales sont exclues)
- **Recherche multi** : `/api/tmdb/search?q=` renvoie films ET séries avec le bon type
- **📺 Chaînes TV en direct** : les 78 chaînes actives du site (généralistes, sport, kids, cinéma, docs, musique…) dans une section dédiée — le flux HLS de chaque chaîne est extrait directement
- **Lecture — le lecteur maison du site + 7 agrégats en parallèle** (l'ID TMDB sert de clé) :
  - **Liste officielle du site** : la page `wwembed.wavewatch.top/api/v1/streaming/ww-{movie|tv}-…` contient `var _src=[…]` — **37 lecteurs avec leur langue** (VF/VOSTFR/MULTI/VO). Les ~25 lecteurs externes (VidFast, VidSrc×4, Frembed, 2Embed, MultiEmbed, Smashy, CineFuse, CineSrc, AnyEmbed, Bcine, MoviePire, Movix.fun, Vidora, CinemaOS, VidKing, Braflix, Mostream, 111movies, MoviesApi, HNEmbed, Peachify, Embed-API…) sont tous essayés
  - **zeus** (`zeus.php?sse`) — flux Server-Sent Events : ~35 sources avec **vraies langues VF/VFQ/VOSTFR/MULTI** et HLS direct 1080p
  - **apiwiflix** — 6–15 liens hébergeurs avec langue
  - **playerix** — jusqu'à 118 boutons « Serveur » : liens dédupliqués par hôte+langue + **playlists HLS directes** (paramètres anglais `season=`/`episode=` — le site passe `saison=`, ignoré par l'API, qui servait l'épisode 1×1 en boucle !)
  - **movix** — ~12 liens FR (Uqload, LuluStream, Voe, DSVPlay…)
  - **french-stream** — vraies étiquettes **VFQ / VF / VOSTFR** (films)
  - **mouve** (`mouve.php?json=1`) — 39–75 flux par contenu, HLS directs inclus
  - **1embed.cc** — playlists HLS directes (extracteur maison)
- **Ordre intelligent** : **VF/VFQ d'abord**, puis VOSTFR, puis MULTI, puis langues inconnues — 6 extractions en parallèle
- **Langue affichée sur chaque lien** : « Filemoon · VF », « Vidzy · VOSTFR », « WaveWatch · Necro · MULTI »…
- **Filtre anti-erreurs** : images, pubs, YouTube, sous-titres exclus du fallback générique

## 📥 Installation

Mêmes méthodes que AnimoFlix (dépôt ci-dessus), ou directement :

- **`.cs3` v1** : release [**wavewatch-v1 (Pre-release)**](https://github.com/j97970293-lang/plugin-fr/releases/tag/wavewatch-v1) → **Paramètres → Extensions → Installer un fichier**
- Miroirs x0.at : `repo.json` → https://x0.at/usfG.json · `plugins.json` → https://x0.at/Zgvv.json · `WaveWatchProvider.cs3` → https://x0.at/rt6A.cs3

## ⚠️ Notes techniques

- Le site est une SPA React : **toutes les données viennent de l'API JSON publique** (`/api/tmdb/…`), rien à scraper côté HTML.
- Les pages `wwembed` (lecture) contiennent la liste des sources en clair (`var _src=[…]`) — l'« anti-bot » du site (Étape 1 → Étape 2) n'est qu'une pub affichée dans le navigateur, sans effet sur l'API.
- Certaines URL de la liste du site sont boguées côté serveur (`apiwiflix.php?id=108978/1`, `&=1`, `tmdb=108978season=1`…) : l'extension **reconstruit** toutes les URL des agrégateurs internes avec les bons paramètres.
- Mouve étiquette tous ses flux « VO » (placeholder) : ces liens sont traités comme langue inconnue.
- Zeus/mouve/playerix peuvent renvoyer 404 en rafale : chaque agrégateur est isolé (`runCatching`), les autres compensent.

---

## 🔨 Compiler soi-même

```bash
./gradlew AnimoFlixProvider:make   # → AnimoFlixProvider/build/AnimoFlixProvider.cs3
./gradlew makePluginsJson          # → build/plugins.json
```

Prérequis : JDK 17 + Android SDK (`platforms;android-35`, `build-tools`). La tâche `make` peut aussi être lancée pour tous les modules via `./gradlew make`.

Pour déployer directement sur un téléphone connecté en USB (ADB) :

```bash
./gradlew AnimoFlixProvider:deployWithAdb
```

## 📁 Structure du projet

```
animoflix-cloudstream/
├── build.gradle.kts              # config racine (plugin Gradle CloudStream, AGP 8.7.3, Kotlin 2.3.0)
├── settings.gradle.kts           # inclusion automatique des modules
├── repo.json                     # descripteur du dépôt (la version à jour est générée sur la branche builds)
├── releases/                     # derniers .cs3 compilés
├── .github/workflows/build.yml   # build automatique → branche builds
├── AnimoFlixProvider/
│   ├── build.gradle.kts          # métadonnées du plugin (version, tvTypes, langue, icône…)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/animoflix/
│           ├── AnimoFlixProvider.kt   # provider (accueil, recherche, fiche, épisodes, liens vidéo)
│           └── (plugin + extracteur AnsEmbed dans le même fichier)
└── ZenixProvider/
    ├── build.gradle.kts          # métadonnées du plugin Zenix
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/zenix/
            └── ZenixProvider.kt        # provider + plugin + extracteur 1embed
```

## ⚠️ Notes techniques

- **`animoflix.to` est protégé par Cloudflare** : au premier chargement, CloudStream ouvre une WebView pour résoudre le challenge, puis les cookies sont réutilisés.
- Les épisodes **VOSTFR** sont mappés sur l'onglet *Subbed* et les **VF** sur l'onglet *Dubbed* de CloudStream.
- Les URLs du site suivent le schéma `/anime/{slug}/[saison-N|film|…]/{vf|vostfr}/episode-N/` — les saisons sans numéro (film, heroines, kai…) sont placées en **saison 0** (spéciaux).
- Si le site change de structure, ajustez les sélecteurs dans `AnimoFlixProvider.kt` et bumpsez `version` dans `AnimoFlixProvider/build.gradle.kts` (déclenche la mise à jour auto côté app).

## 📜 Licence / Avertissement

Projet à but éducatif. CloudStream et cette extension ne hébergent aucun contenu : elles indexent uniquement un site public. Utilisez-la conformément aux lois applicables dans votre pays.

---

# Afterdark · Extension CloudStream (`.cs3`)

> **v4** : plus de serveurs — miroirs Vidara (16 domaines), BlinkFlux, lecteurs vidzy/fsvid décodés, docteur assoupli (403/416/429 conservés).

> **v3** : correctif erreurs 3003 — docteur de liens (chaque lien vérifié jouable avant affichage) + extracteur Vidara réparé + doublons fusionnés.

> **v2** : bouton « Réglages » pour changer l'adresse du site ; secours anime AnimoFlix (One Piece & co, derniers épisodes inclus) ; en TV seuls les liens playerix/mouve fiables sont gardés (les autres renvoyaient le même épisode pour tous).

Extension pour **[afd926.mom](https://afd926.mom/)** (miroir actif de `afterdark.best`) — films et séries en **VOSTFR**.

- **Catalogue** : les données TMDB du site, servies en français (tendances films & séries, prochaines sorties, découvertes par genre, recherche multi).
- **Lecture — un maximum de serveurs en parallèle** :
  - les **lecteurs de secours du site lui-même** (Videasy, Frembed, Peachify — URL exactes tirées de son code) ;
  - les agrégateurs **apiwiflix, playerix, zeus, mouve, movix, french-stream et 1embed** (playlists HLS directes) ;
  - les lecteurs publics **VidFast, VidSrc.cc, VidSrc.wtf, 2Embed, 111Movies, Braflix, VidKing**.
- **Ordre des liens : VOSTFR d'abord**, puis VO, puis VF/VFQ, puis MULTI — logique d'un site VOSTFR. La langue est affichée sur chaque lien : « Vidara · VOSTFR », « Kakaflix · VFQ »…
- ~50–60 liens par film, ~45–55 par épisode (hébergeurs extractibles + playlists HLS directes).
- ℹ️ L'API vidéo interne du site est protégée par **Cloudflare Turnstile** (vérifié par reverse-engineering des bundles : un « proof » signé est exigé pour `/api/sources`). L'extension livre donc le contenu via des sources publiques éprouvées, indexées sur le même ID TMDB — résultat équivalent, sans captcha.
- Certains lecteurs publics (VidSrc.cc, Peachify) bloquent les serveurs/datacenters : ils peuvent fonctionner depuis votre appareil mais pas depuis un cloud.

## 📥 Installation

| Extension | Fichier | Miroir x0.at |
|---|---|---|
| Afterdark v4 | [AfterdarkProvider.cs3](releases/AfterdarkProvider.cs3) | [AfterdarkProvider.cs3](https://x0.at/9jiS.cs3) |
| AnimoFlix v5 | [AnimoFlixProvider.cs3](releases/AnimoFlixProvider.cs3) | [AnimoFlixProvider.cs3](https://x0.at/jw4w.cs3) |
| Franime v1 | [FranimeProvider.cs3](releases/FranimeProvider.cs3) | [FranimeProvider.cs3](https://x0.at/rWQ4.cs3) |
| WaveWatch v4 | [WaveWatchProvider.cs3](releases/WaveWatchProvider.cs3) | [WaveWatchProvider.cs3](https://x0.at/SjF7.cs3) |
| Zenix v5 | [ZenixProvider.cs3](releases/ZenixProvider.cs3) | [ZenixProvider.cs3](https://x0.at/KMnW.cs3) |

| Dépôt (repo.json) | Liste d'extensions (plugins.json) |
|---|---|
| https://x0.at/oWUU.json | https://x0.at/1e0V.json |

> ⚠️ Miroirs du 14/09 (nuit, FRAnime inclus) — les plus récents. Les précédents (`Zlel`/`snlN`, `PEvE`/`hkF6`…) resteront accessibles mais ne servent pas la nouvelle extension : remplacez l'URL du dépôt par celle ci-dessus, ou mettez simplement à jour chaque extension.

---

# FRAnime · Extension CloudStream (`.cs3`)

> **v1** : extension pour **[franime.fr](https://franime.fr/)** — animes en **VF & VOSTFR** : catalogue Kitsu (tendances, en cours, populaires, mieux notés, films), saisons avec miniatures, films détectés automatiquement, lecteurs multiples (Sibnet, VidMoly, SendVid, FileMoon, Uqload, Streamtape, Dood, VK…), décodage du jeton chiffré `watch2`, anti-leurres, docteur de liens.

Extension pour **[franime.fr](https://franime.fr/)** — le catalogue d'animes français de référence, en **VF & VOSTFR**.

## ✨ Fonctionnalités

- **Catalogue & recherche via Kitsu** : FRanime indexe les fiches **Kitsu** (IDs identiques). L'extension interroge l'API publique `kitsu.io` pour les listes (Tendances, En cours de diffusion, Populaires, Mieux notés, Films d'animation) et la recherche — **le catalogue complet du site (~11 Mo) n'est jamais téléchargé**, les fiches s'ouvrent en ~100 Ko.
- **Fiches riches** : synopsis, affiche, fond, note, année, statut (en cours/terminé), titres alternatifs — tout vient de Kitsu, en français côté CloudStream.
- **Saisons & épisodes** : structure complète multi-saisons avec numéros, titres et **miniatures** par épisode ; **films détectés automatiquement** (fiche « film » au lieu d'une liste d'épisodes).
- **Lecture — VF + VOSTFR, plusieurs lecteurs** : pour chaque épisode, l'extension sonde les lecteurs **VOSTFR puis VF** (jusqu'à 5 par langue) et affiche tous ceux qui répondent, étiquetés « **Sibnet · VOSTFR** », « **VidMoly · VF** »…
- **Décodage `watch2`** : l'API lecteur renvoie une URL chiffrée (`base64 → hex → XOR`) — l'extension la déchiffre automatiquement (clé trouvée par essais, format validé).
- **Anti-leurres** : le site sert parfois un embed générique aux clients non-navigateur — liste noire intégrée + détection par doublons (deux lecteurs qui résolvent le même embed = leurre → langue ignorée).
- **Cloudflare** : le challenge du site et de son API est résolu sur l'appareil (WebView intégrée à CloudStream) — aucune manipulation.
- **Docteur de liens** : chaque flux est vérifié jouable (HLS/MP4/WebM/TS/DASH) avant affichage — pas d'erreur 3003.
- **Bouton « Réglages »** : changez l'adresse du site si franime.fr déménage (l'API suit automatiquement : `api.<domaine>`).

## 📥 Installation

Méthode habituelle : ajoutez le dépôt (voir [Méthode 1](#-installation) plus haut) puis installez **FranimeProvider** — ou directement :

| Extension | Fichier | Miroir x0.at |
|---|---|---|
| Franime v1 | [FranimeProvider.cs3](releases/FranimeProvider.cs3) | [FranimeProvider.cs3](https://x0.at/rWQ4.cs3) |

| Dépôt (repo.json) | Liste d'extensions (plugins.json) |
|---|---|
| https://x0.at/oWUU.json | https://x0.at/1e0V.json |

## ⚠️ Notes techniques

- **Kitsu ≠ FRanime** : les listes montrent tout Kitsu ; si un titre n'est pas sur FRanime, la fiche l'indique (« Cet anime n'est pas disponible sur FRAnime »). Cherchez un titre voisin — le catalogue FRanime est très large (plusieurs milliers de fiches).
- **Indice des lecteurs** : l'API lecteur prend l'index 0-based du lecteur (convention vérifiée sur 4 implémentations indépendantes) — l'extension sonde 0–4 et ignore les réponses vides.
- **VOSTFR d'abord**, VF ensuite — même logique que les autres extensions du dépôt.

## 🔨 Compiler soi-même

```bash
git clone https://github.com/j97970293-lang/plugin-fr
cd plugin-fr
./gradlew :FranimeProvider:make   # → FranimeProvider/build/FranimeProvider.cs3
```

---

Projet à but éducatif. CloudStream et cette extension ne hébergent aucun contenu : elles indexent uniquement un site public. Utilisez-la conformément aux lois applicables dans votre pays.
