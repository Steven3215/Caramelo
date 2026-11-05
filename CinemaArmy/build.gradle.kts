version = 2

cloudstream {
    description = "Cinema Army movies and TV shows. Initial implementation; device testing pending."
    authors = listOf("Caramelo")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
