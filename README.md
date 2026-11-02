# Cloudstream providers

Private Cloudstream extensions for Steven3215.

## Cinema Army

Initial implementation for https://cinema.army:
- Trending, popular and top-rated movies; popular TV shows.
- Search, posters, descriptions, genres, ratings, seasons and episodes.
- Four playback servers with per-server failure handling and the site's normal stream-token protocol.
- Plain and encoded HLS URLs, with optional subtitles from the service used by the website.

The provider is marked **beta-only (status 3)** until it has been tested on a device.
A catalog entry does not guarantee that any server has a playable stream.
Search uses the site's single-page search endpoint. Trending/top-rated rows are
single-page; popular rows use the API's reported pagination. Seasons that fail to
load are skipped so available seasons remain usable.

Protocol values come from the site's publicly served JavaScript. If the site
changes its API or encoding, the provider may need updating. No GitHub credentials
or personal account secrets are embedded.

## Build and install

Open **Actions → Build private extension artifact**. Builds run on relevant pushes
and can also be started with **Run workflow**. After a successful run, download the
**CinemaArmy** artifact, unzip it, and install the .cs3 file using Cloudstream's
local extension installation mechanism. Artifacts expire after seven days.

The workflow runs unit tests, compiles the provider and uploads the private artifact.
A separate non-blocking live smoke check tests catalog/search/details/TV episodes
and an HLS playlist for Night of the Living Dead (1968). Its outcome is visible
in the workflow log. This does not replace on-device playback testing.

For local builds, first check out the public build plugin at
`build-tools/cloudstream-gradle`, revision
`7cc64f92d88350ecbf775d6332de14dcaa71dcda`. GitHub Actions does this automatically.

Local prerequisites: Java 17, Android SDK 35 and Gradle 8.12 (no wrapper included).

```text
gradle CinemaArmy:testDebugUnitTest CinemaArmy:make
python3 tools/smoke_cinema.py
```

## Privacy and source

The repository and build artifacts remain private. There is no public release,
builds branch, or repo.json feed. A private GitHub raw URL cannot be used as an
unauthenticated Cloudstream repository feed. Never embed a GitHub token into a URL.

Build configuration is based on the public-domain
[Cloudstream TestPlugins template](https://github.com/recloudstream/TestPlugins).
The Gradle plugin source revision is pinned; the Cloudstream API JAR uses the pre-release channel.
