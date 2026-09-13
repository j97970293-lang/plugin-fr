// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Zenix — Films et séries en VF/VOSTFR"
    authors = listOf("ArenaAgent")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Movie", "TvSeries")

    language = "fr"

    iconUrl = "https://www.google.com/s2/favicons?domain=zenix.best&sz=%size%"
}
