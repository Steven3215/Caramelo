# Caramelo

Caramelo extensions for Cloudstream.

## Movy

Movies and TV shows from https://www.movy.sx/, including paginated movie/TV/genre rows,
search (first result page), movie details, seasons, episodes, quality choices and available subtitles.
Open the extension's settings/gear button to toggle homepage rows and select playback servers.
Four original-audio servers are enabled by default. Additional original-audio, German, French,
Hindi and Spanish servers are optional; not every title exists on every server.
Disabling all servers produces an instruction to enable one. Switch sources or restart after homepage changes.

Catalog and playback requests follow the website's normal public client. Source metadata uses
a short-lived public seed and the client response decoder; no account secrets or video DRM keys are embedded.
HLS playlists are checked before links are offered; direct MP4 links may also be offered when present.
The automated live check uses Night of the Living Dead (1968) and reads only a bounded media prefix in memory.
Streams may be offline, blocked by an upstream service or unavailable for a catalog entry.
Beta-only; Android playback, casting and settings UI remain device-test pending.
Build and unit tests passed. Local movie playback and a TV episode playlist were verified;
the GitHub-hosted runner's stream requests received HTTP 403, so stream availability can depend on the network.

## Global Free TV

Live channel directory from https://www.globalfreetv.com/, including all listed categories and countries.
Open the extension's settings/gear button to toggle homepage rows, group by categories/countries/both,
filter browsing and search by country, and choose most-liked/alphabetical/newest order.
Recently Added always uses newest order. Row toggles affect homepage sections; the separate country
filter applies to all rows and search. An enabled empty country selection shows no channels.
Switch sources or restart Cloudstream after changing homepage rows. New directory entries are learned while browsing.

The API pages at most 100 channels. Country filtering uses complete cached lists before local pagination,
so sparse matches are not lost. Large filtered lists may take longer to load initially; cache lasts ten minutes.
UK/GB and PL/pl directory aliases are combined. HLS responses are verified; supplied broadcaster Referer
and User-Agent metadata are respected. YouTube links use Cloudstream's maintained extractor and depend on its availability.
Offline, region-restricted, or unsupported sources can have no playable links. Beta-only; device testing pending.

## Sportsbite

Version 2 registers **Sportsbite Sports** and **Sportsbite Live TV** from one extension.
Sports includes the live/upcoming schedule, all listed sports and an Other Sports row.
Live TV includes non-sports channels, category and country browsing, search and pagination.
Matching channel names within the same country share a card and offer alternate servers.

Open **Settings → Extensions → Caramelo → Sportsbite → settings/gear**:
- Toggle each sports homepage row, TV category row and TV country row.
- Group TV by categories (default), countries, or both.
- Optionally restrict TV browsing and search to selected countries; All countries clears the filter.
- Hide finished sports events (default on), or only show events with listed servers (default off).
- Reset settings to restore defaults.

Row toggles control homepage sections; they do not remove channels from search or the All Live Channels row.
The separate country filter applies to all TV rows and search. A selected empty country filter shows no channels.
Switch to another source and back, or restart Cloudstream, after changing homepage settings.
New TV categories and countries are learned from the current catalog; reopen the source to see new rows.

Playback verifies HLS responses, supports the site's first-party playlist routes and self-hosted raw mode,
and decodes image-container segments locally. Listed servers can still fail upstream or stop after an event.
Device testing is pending; beta-only. Casting is unsupported.

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

Keep the leading exclamation mark. Then open **Caramelo**
and install **Cinema Army**, **UniqueStream Anime**, or **Sportsbite**. The provider is currently marked beta-only; use the
Cloudstream prerelease build if it is unavailable in the stable app.

Full repository URL (fallback):

https://raw.githubusercontent.com/Steven3215/Caramelo/main/repo.json

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
gradle CinemaArmy:testDebugUnitTest UniqueStreamAnime:testDebugUnitTest Sportsbite:testDebugUnitTest CinemaArmy:make UniqueStreamAnime:make Sportsbite:make
python3 tools/smoke_cinema.py
python3 tools/smoke_unique.py
python3 tools/smoke_sportsbite.py
```

## Repository and source

This repository is public, including source code, the extension download and feed.
Do not put credentials into repository URLs or source files.

Build configuration is based on the public-domain
[Cloudstream TestPlugins template](https://github.com/recloudstream/TestPlugins).
The Gradle plugin source revision is pinned; the Cloudstream API JAR uses the pre-release channel.

## Repository display name

The current feed and shortcode use **Caramelo**. If Cloudstream still displays an older
repository name, remove that repository entry and add the current feed again with the name Caramelo.
Refresh/reinstall extensions if the cached author label still shows the old branding.
