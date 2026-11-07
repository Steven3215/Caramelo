"""Check the public JustAnime and ThugTV catalog and playback paths."""

import json
from urllib.parse import urljoin
from urllib.request import Request, urlopen


def read(url, origin, size=3_000_000):
    headers = {"User-Agent": "Mozilla/5.0", "Origin": origin, "Referer": origin + "/"}
    with urlopen(Request(url, headers=headers), timeout=20) as response:
        return response.read(size), response.url


def api(base, path, origin):
    return json.loads(read(base + path, origin)[0])


def media(url, origin):
    for _ in range(4):
        body, url = read(url, origin, 65_536)
        if not body.startswith(b"#EXTM3U"):
            assert len(body) > 512 and not body.lstrip().startswith((b"<", b"{")), "Invalid media response"
            return
        links = [line.strip() for line in body.decode().splitlines() if line.strip() and not line.startswith("#")]
        assert links
        url = urljoin(url, links[0])
    raise AssertionError("Playlist chain did not yield a media segment")


just = "https://justanime.to"
core = "https://core.justanime.to/api"
assert api(core, "/home", just)["trending"]
assert api(core, "/search/suggestions?query=black%20clover", just)["data"]
assert api(core, "/anime/97940", just)["data"]["id"] == 97940
assert api(core, "/anime/97940/episodes?page=1", just)["episodes"][0]["number"] == 1
path = "/watch/97940/episode/1"
assert api(core, path + "/servers", just)["servers"]["revo"]["sub"]
bundle = api(core, path + "/revo", just)
for audio in ("sub", "dub"):
    media(bundle[audio]["sources"][0]["url"], just)
print("PASS JustAnime catalog, search, episodes and SUB/DUB HLS media")

thug = "https://thugtv.online"
backend = "https://api.thugtv.online/api"
assert api(backend, "/tmdb/trending", thug)
assert api(backend, "/tmdb/search?q=night%20of%20the%20living%20dead", thug)
assert api(backend, "/tmdb/movie/10331", thug)["id"] == 10331
assert api(backend, "/tmdb/tv/1399/season/1", thug)["episodes"]
sources = api(backend, "/stream/movie/10331", thug)["sources"]
path = "/stream/direct/vaplayer/movie/10331"
assert any(item.get("resolvePath") == path for item in sources)
media(api(backend, path, thug)["source"]["url"], thug)
print("PASS ThugTV catalog, search, seasons and public-domain movie HLS media")
