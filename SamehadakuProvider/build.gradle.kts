// use an integer for version numbers
version = 25


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
     authors = listOf("arranoust")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://v1.samehadaku.how/wp-content/uploads/2020/04/cropped-download-1-192x192.jpg"
}