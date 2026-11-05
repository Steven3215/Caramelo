version = 1
cloudstream {
    description = "AnimeSuge anime catalog, SUB/DUB episodes and MegaPlay HLS with subtitles. Local playback; device testing pending."
    authors = listOf("Caramelo")
    status = 3
    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
