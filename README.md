# Cloudstream providers

Cloudstream extensions for Steven3215.

## UniqueStream Anime

Provider for https://anime.uniquestream.net/ with catalog/search, anime movies,
paginated seasons and episodes, Japanese/original and English audio, signed HLS
playback and subtitles. The player uses an in-memory interceptor for the site's
ordinary HLS key transport. Chromecast is unsupported because its receiver cannot
run that interceptor. Streams that are processing or unavailable are not queued.

Device testing is pending; this provider is marked beta-only.

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

## Install in Cloudstream

Open **Settings → Extensions → Add repository** and enter the case-sensitive shortcode:

```text
!WYx67
```

Keep the leading exclamation mark. Then open **Steven's Cloudstream Providers**
and install **Cinema Army** or **UniqueStream Anime**. The provider is currently marked beta-only; use the
Cloudstream prerelease build if it is unavailable in the stable app.

Full repository URL (fallback):

https://raw.githubusercontent.com/Steven3215/cloudstream-providers/main/repo.json

The shortcode redirects directly to this feed. Compiled downloads are published
under downloads/ and verified using the SHA-256 checksum in plugins.json.

## Build and release updates

The GitHub Actions build workflow runs unit tests and packages a .cs3 artifact.
It also runs a non-blocking live API and HLS playlist check using Night of the
Living Dead (1968). Artifacts expire after seven days.

To publish a new version, replace the matching .cs3 file under downloads/ and update its version,
fileSize and fileHash in plugins.json together. The feed URL and shortcode remain
unchanged. The current build workflow produces artifacts; it does not automatically
replace the published download.

For local builds, first check out the public build plugin at
`build-tools/cloudstream-gradle`, revision
`7cc64f92d88350ecbf775d6332de14dcaa71dcda`. GitHub Actions does this automatically.

Local prerequisites: Java 17, Android SDK 35 and Gradle 8.12 (no wrapper included).

```text
gradle CinemaArmy:testDebugUnitTest UniqueStreamAnime:testDebugUnitTest CinemaArmy:make UniqueStreamAnime:make
python3 tools/smoke_cinema.py
```

## Repository and source

This repository is public, including source code, the extension download and feed.
Do not put credentials into repository URLs or source files.

Build configuration is based on the public-domain
[Cloudstream TestPlugins template](https://github.com/recloudstream/TestPlugins).
The Gradle plugin source revision is pinned; the Cloudstream API JAR uses the pre-release channel.
