version = 2
cloudstream {
    description = "Moonflix movies and TV with direct HLS sources where available."
    authors = listOf("Caramelo")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
