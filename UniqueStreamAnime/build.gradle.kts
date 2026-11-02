version = 1

cloudstream {
    description = "AnimeStream by UniqueStream: anime series and movies, audio choices and subtitles. Device testing pending."
    authors = listOf("Steven3215")
    status = 3
    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
