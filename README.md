# Plugin fr · Dépôt d'extensions CloudStream

**24 extensions** (films, séries, animes, TV live, 18+) pour [CloudStream](https://github.com/recloudstream/cloudstream), mises à jour automatiquement par CI.

| Extension | Site | Contenu |
|---|---|---|
| **AnimoFlixProvider** | animoflix.to | animes, films & OAV en VF / VOSTFR |
| **ZenixProvider** | zenix.best | films & séries en VF / VOSTFR |
| **WaveWatchProvider** | wavewatch.top | films, séries, animes & **TV en direct** |
| **AfterdarkProvider** | afd926.mom | films & séries en VOSTFR (multi-serveurs) |
| **FranimeProvider** | franime.fr | animes VF & VOSTFR (saisons, films) |
| **FrenchStreamProvider** | fs27.lol | films, séries & animes VF / VFQ / VOSTFR |
| **FlemmixProvider** | flemmix.cloud | films & séries — jusqu'à 16 lecteurs par titre |
| **VostfreeProvider** | vostfree.ws | animes VF & VOSTFR + films d'animation |
| **AnimeSamaProvider** | anime-sama.to | animes VF & VOSTFR — saisons, films, sagas |
| **AnimeSiteProvider** | animesite.fr | animes VF & VOSTFR — MP4 directs SibNet |
| **CineStreamProvider** | cinestream.info | films VF/VOSTFR — 15 lecteurs par titre |
| **PurstreamProvider** | purstream.ad | films & séries — flux HLS directs |
| **UnJour1FilmProvider** | 1jour1film0826b.website | films & séries VF/VOSTFR (8 400+ titres) |
| **TeleFranceProvider** | iptv-org (GitHub) | **TV en direct** — 200+ chaînes FR, 🇲🇬 Madagascar (TVM, RTA…) & 🌍 francophonie |
| **HentaiCityProvider** | hentaicity.com | 🔞 hentai & 3D animés — HLS directs |
| **HentaiHavenProvider** | hentaihaven.xxx | 🔞 Rule34 animé — HLS directs |
| **XvideosProvider** | xvideos.com | 🔞 vidéos amateurs & studios — HLS directs |
| **MovixProvider** | movix.men | films & séries VF — 13+ serveurs réels par contenu + agrégateur |
| **TrixHentaiProvider** | trixhentai.com | 🔞 hentai **VOSTFR** — MP4 directs |
| **PornovoreProvider** | pornovore.fr | 🔞 films X **français** — MP4 directs |
| **AdkamiHentaiProvider** | hentai.adkami.com | 🔞 hentai **VOSTFR/raw** — catalogue Adkami |
| **HentaiFapProvider** | hentai-fap.fr | 🔞 hentai **VOSTFR/VOSTA/RAW** — MP4 directs (streaming de hentai-paradise.fr) |
| **HentaiVostProvider** | hentaivost.fr | 🔞 hentai **VOSTFR** — CloudflareKiller intégré |
| **HentaiStreamProvider** | hentaistream.io | 🔞 hentai **EN sous-titré** — m3u8 multi-qualités |

## Installation

1. Ouvrez CloudStream → **Paramètres ⚙ → Extensions → Ajouter un dépôt**
2. Collez : `https://raw.githubusercontent.com/j97970293-lang/plugin-fr/builds/repo.json`
3. Installez les extensions souhaitées depuis le dépôt « Plugin fr »

<details>
<summary>Miroirs (si GitHub est inaccessible)</summary>

- repo.json (v11.4, 24 sources) : `https://x0.at/dyyE.json`
- plugins.json (liste des 24 extensions, v11.4) : `https://x0.at/RuRD.json`
- WaveWatch v7 : `https://x0.at/dDfv.cs3` · Zenix v8 : `https://x0.at/tYS4.cs3` · 1JOUR1FILM v6 : `https://x0.at/jWyq.cs3` · CineStream v5 : `https://x0.at/h484.cs3` · Télé FR Direct v2 (Madagascar 🇲🇬) : `https://x0.at/MpNP.cs3` · AnimeSama v6 : `https://x0.at/i1wf.cs3` · AnimeSite v5 : `https://x0.at/ABrQ.cs3` · FrenchStream v5 : `https://x0.at/943P.cs3` · Purstream v5 : `https://x0.at/yD9r.cs3` · Vostfree v4 : `https://x0.at/Mozr.cs3`

Les adresses des miroirs sont re-publiées à chaque version.
</details>

## Documentation

- 📒 **[CHANGELOG.md](CHANGELOG.md)** — journal des mises à jour
- 🛠 **[TUTO.md](TUTO.md)** — guide de création d'extensions CloudStream (méthode d'analyse, extraits, pièges)

## Avertissement

Ce dépôt ne fait qu'indexer des sites publics tiers ; il n'héberge aucun contenu. À usage strictement personnel — respectez les lois de votre pays.

### Miroirs des nouveautés v11.4 (x0.at)
- MovixProvider.cs3 (v5 — refonte sur le vrai site movix.men, 13+ serveurs) : `https://x0.at/uYRa.cs3`

### Miroirs des nouveautés v11.3 (x0.at)
- MovixProvider.cs3 (v4 — recherche réparée + chargement parallèle) : `https://x0.at/Lm5H.cs3`
- HentaiFapProvider.cs3 (v3 — sections + retry) : `https://x0.at/eqU8.cs3`
- HentaiVostProvider.cs3 (v3 — sections + retry) : `https://x0.at/eaCc.cs3`
- PurstreamProvider.cs3 (v6 — agrégateur) : `https://x0.at/dI9D.cs3`
- FrenchStreamProvider.cs3 (v6 — agrégateur films) : `https://x0.at/DsHj.cs3`

### Miroirs des nouveautés v11.2 (x0.at)
- MovixProvider.cs3 (v3 — supplément agrégateur multi-serveurs) : `https://x0.at/8uMp.cs3`
- HentaiFapProvider.cs3 (v2 — catalogue réparé) : `https://x0.at/JFH4.cs3`
- HentaiVostProvider.cs3 (v2 — moteur pont, sans timeout) : `https://x0.at/2iKA.cs3`
- plugins.json (25 extensions, v11.2) : `https://x0.at/Z4A3.json`

### Miroirs des nouveautés v11.1 (x0.at)
- MovixProvider.cs3 (v2 — serveurs réparés) : `https://x0.at/VfpK.cs3`
- HentaiFapProvider.cs3 (v1) : `https://x0.at/pV57.cs3`
- HentaiVostProvider.cs3 (v1) : `https://x0.at/x2C6.cs3`

### Miroirs v11 (x0.at)
- AdkamiHentaiProvider.cs3 (v1) : `https://x0.at/VBSD.cs3` · HentaiStreamProvider.cs3 (v1) : `https://x0.at/wu0p.cs3` · TrixHentaiProvider.cs3 (v2) : `https://x0.at/M6ld.cs3` · PornovoreProvider.cs3 (v2) : `https://x0.at/cWUp.cs3`

### Miroirs v10 (x0.at)
- HentaiCityProvider.cs3 : `https://x0.at/xeY1.cs3`
- HentaiHavenProvider.cs3 : `https://x0.at/DVAe.cs3`
- XvideosProvider.cs3 : `https://x0.at/AwtX.cs3`
- TeleFranceProvider.cs3 (v3) : `https://x0.at/2Bk3.cs3`
