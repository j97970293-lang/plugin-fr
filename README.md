# Plugin fr · Dépôt d'extensions CloudStream (`.cs3`)

Dépôt d'extensions [CloudStream](https://github.com/recloudstream/cloudstream) en français — **trois extensions** :

| Extension | Site | Contenu |
|---|---|---|
| **AnimoFlixProvider** | [animoflix.to](https://animoflix.to/) | animes, films & OAV en VF / VOSTFR |
| **ZenixProvider** | [zenix.best](https://zenix.best/) (secours : zenix.lol) | films & séries en VF / VOSTFR |
| **WaveWatchProvider** | [wavewatch.top](https://wavewatch.top/) | films, séries, animes & **TV en direct** en VF / VOSTFR |

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
