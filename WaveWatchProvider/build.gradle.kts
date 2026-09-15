// Use an integer for version numbers
version = 6

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "WaveWatch — Films, séries, animes & TV en direct (VF/VOSTFR)"
    authors = listOf("j97970293-lang")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Movie", "TvSeries", "Anime", "Live")

    language = "fr"

    iconUrl = "https://www.google.com/s2/favicons?domain=wavewatch.top&sz=%size%"
}
