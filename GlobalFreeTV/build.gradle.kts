version = 2
cloudstream {
    description = "Global Free TV live channels with category/country homepage controls, country filtering and sorting. Device testing pending."
    authors = listOf("Caramelo")
    status = 3
    tvTypes = listOf("Live")
    language = "en"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
