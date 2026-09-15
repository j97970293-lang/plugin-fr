// Use an integer for version numbers
version = 3

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "FRAnime — Animes VF/VOSTFR, films & OAV (lecteurs Sibnet, VidMoly, FileMoon, SendVid, Uqload…)"

    authors = listOf("j97970293-lang")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    language = "fr"

    iconUrl = "https://www.google.com/s2/favicons?domain=franime.fr&sz=%size%"
}
