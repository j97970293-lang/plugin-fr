// Use an integer for version numbers
version = 5

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Afterdark — Films et séries en VOSTFR (multi-serveurs)"
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

    iconUrl = "https://www.google.com/s2/favicons?domain=afd926.mom&sz=%size%"
}
