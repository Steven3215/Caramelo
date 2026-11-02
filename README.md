# Cloudstream providers

Private Cloudstream extension development repository for Steven3215.

## Current status

Cinema Army (https://cinema.army/movies) is the first target. The module is a
**nonfunctional skeleton**, marked status 0 (down). Search, movie details,
and playback extraction are not implemented. Do not treat it as a working provider.

The site renders in a browser using JavaScript. Its catalog requests, identifiers,
pagination, and player behavior still need to be verified before implementation.
No authentication secrets are stored in this project.

## Build

The build configuration is adapted from the public-domain
[Cloudstream TestPlugins template](https://github.com/recloudstream/TestPlugins).
It uses Java 17, Android SDK 35, and Gradle 8.12.

In GitHub, open **Actions → Build private extension artifact → Run workflow**.
The workflow is manual only. It uploads a private artifact retained for seven days;
it does not create public releases or publish a repository feed.

For a local build with Java 17, Android SDK 35 and Gradle 8.12 installed:

```text
gradle CinemaArmy:make
```

No Gradle wrapper is included. Build and on-device behavior have not yet been validated.
Cloudstream's upstream Gradle plugin and API dependencies are moving versions.

## Private distribution

A private GitHub raw URL cannot serve as a normal unauthenticated Cloudstream
repository feed. Download compiled .cs3 files from an authenticated GitHub session
and use Cloudstream's local extension installation mechanism on your device.

No repo.json or public builds branch is provided. Never put GitHub tokens into
repository URLs or extension source files.

## Remaining provider work

- Verify the site's catalog and search requests and response schema.
- Implement search and movie detail loading using observed site behavior.
- Implement supported playback extraction and subtitle handling.
- Compile and test search, details, and playback on Cloudstream.
- Enable the provider only after those checks pass.
