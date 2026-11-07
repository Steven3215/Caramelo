version = 1
cloudstream {
    description = "Corsflix movies and TV shows, configurable homepage rows and third-party embed servers. Device testing pending."
    authors = listOf("Caramelo")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
