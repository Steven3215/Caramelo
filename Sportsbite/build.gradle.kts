version = 5
cloudstream {
    description = "Sportsbite Sports and Live TV, with configurable homepage categories, countries and filters. Local playback; device testing pending."
    authors = listOf("Caramelo")
    status = 3
    tvTypes = listOf("Live")
    language = "en"
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
