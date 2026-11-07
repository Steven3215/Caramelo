# Caramelo

Caramelo extensions for Cloudstream.

## Audio and subtitles

In Cloudstream 4.8.0, Tracks shows video resolutions and displays audio choices when the selected
stream contains multiple audio tracks. Caramelo keeps HLS master playlists intact and labels
known audio languages in Sources. Select another entry in Sources when a provider delivers
a dub as a separate stream.

Caramelo loads available subtitle files with their language and region, including Latin American
Spanish and Brazilian Portuguese when provided. Embedded subtitles stay in the stream. Availability
depends on the title, episode and upstream server.

UniqueStream lists the returned language versions together in Sources. SoloLatino v2 checks
the offered player options for HLS and MP4 and preserves playback request headers. Its anonymous
embed flow was verified, and Android playback still needs a device check.

## New sources

**Nekowatch** provides anime home rows, search, AniList-based details, real SUB/DUB episode lists,
signed HLS/file sources and available subtitles through the site's anonymous API. Home rows and
search use the site's returned lists rather than inventing episode availability. Anime, episode and
audio identities are checked before accepting playback metadata. Provider availability varies.

**AnimeSuge** provides paginated listings, search, anime details, SUB/DUB episodes and captions.
The current supported player is MegaPlay. Its public metadata transport is decoded using the
normal player format, and image-wrapped MPEG-TS segments use a local interceptor.
Casting is unsupported. Other player hosts may not resolve. No account or video DRM keys are stored.

**Corsflix** provides movies/TV, search, details, seasons and episodes using its own catalog API.
Homepage and server settings are included. The listed 2Embed → StreamWish player chain is parsed,
with an Android WebView fallback for the site's Rive embed. Both routes validate the playlist with the playback headers. Catalog/embed metadata checks pass,
but direct player CDN requests returned HTTP403 in manual testing. Full Android playback is unverified;
this source remains beta and may have no working streams depending on the upstream player.

All three new extensions are beta-only (status 3); device playback and settings testing remain pending.

## Settings for phone, tablet and Android TV

Sportsbite v6 and Global Free TV v4 have grouped settings menus, searchable category/country lists,
selection counts, 56dp option rows and 52dp bulk controls. Select all/Deselect all change every option in the current list, including options hidden by search. Save commits a draft; Cancel discards it.
Grouping and sort changes also use Save/Cancel. Country-filter screens include an All countries action.
Native focusable controls support touch and D-pad navigation; the TV version does not automatically
focus the search keyboard. Existing preferences are retained. Refresh the source or restart Cloudstream
after changing homepage rows. Shared selection logic has unit coverage; Android visual/device QA is pending.

## Lightflix, Moonflix, Nexa and SoloLatino

All four providers include movie and series catalogs, search and episode lists.
Moonflix resolves direct HLS links and handles MPEG-TS segments packaged in PNG chunks.
A sample passed locally and in one hosted run. Direct source availability varies.
Lightflix and Nexa have verified catalogs. SoloLatino resolved anonymous player embeds locally.
These three use experimental Android WebView playback. Device playback remains unverified.

## JustAnime and ThugTV

JustAnime provides anime search, browse rows, and episode lists. Available playback links are
labeled SUB or DUB. Server selection keeps checking for each advertised audio option until it finds a working link. Both audio options passed a live HLS media check.

ThugTV provides movie and series catalogs, search, episodes, and native HLS sources. Homepage pagination skips repeated titles and continues when later pages offer new ones.
A public-domain movie sample played through the HLS media check locally. The hosted runner
received HTTP 403 from the catalog backend. Both extensions are beta and need device testing.

## Teleonline and Tele Libre

Teleonline has channel search and selectable category and country rows. Country rows start off.
Tele Libre has searchable categories. It uses tele-libre.top as a fallback for the matching directory.
Both settings screens include Select all, Deselect all, Save and Cancel.
Canal 24 Horas passed a public HLS media check through both providers. Some Teleonline streams
are region restricted. Tele Libre supports direct HLS and the public RTVE embed path. Encrypted
DASH players are unsupported. Age-gated categories are omitted. Android device checks are pending.

## Movy

Movies and TV shows from https://www.movy.sx/, including paginated movie/TV/genre rows,
search (first result page), movie details, seasons, episodes, quality choices and available subtitles.
Open the extension's settings/gear button to toggle homepage rows and select playback servers.
Defaults include original-audio, English, German, French, Hindi and Spanish servers. Additional original-audio, German, French,
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

Version 6 registers **Sportsbite Sports** and **Sportsbite Live TV** from one extension.
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
and install the providers you want. All extensions are currently marked beta-only; use the
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
gradle CinemaArmy:testDebugUnitTest UniqueStreamAnime:testDebugUnitTest Sportsbite:testDebugUnitTest GlobalFreeTV:testDebugUnitTest Movy:testDebugUnitTest Corsflix:testDebugUnitTest Nekowatch:testDebugUnitTest AnimeSuge:testDebugUnitTest CinemaArmy:make UniqueStreamAnime:make Sportsbite:make GlobalFreeTV:make Movy:make Corsflix:make Nekowatch:make AnimeSuge:make
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
