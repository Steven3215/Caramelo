version = 2
cloudstream {
    description = "Nekowatch anime with SUB/DUB episodes and signed HLS/file playback. Device testing pending."
    authors = listOf("Caramelo")
    status = 3
    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
