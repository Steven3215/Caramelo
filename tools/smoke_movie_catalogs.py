"""Check the public movie catalogs and Moonflix's direct media transport."""

import json
import re
import struct
from http.cookiejar import CookieJar
from urllib.parse import urlencode, urljoin
from urllib.request import HTTPCookieProcessor, Request, build_opener, urlopen


UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")


def read(url, headers=None, data=None, opener=urlopen, limit=3_000_000):
    request = Request(url, headers={"User-Agent": UA, **(headers or {})}, data=data)
    with opener(request, timeout=18) as result:
        return result.read(limit), result.url


def text(url, **options):
    return read(url, **options)[0].decode("utf-8")


def data(url, **options):
    return json.loads(read(url, **options)[0])


def tmdb(path, key):
    return data("https://api.themoviedb.org/3" + path + ("&" if "?" in path else "?") + urlencode({"api_key": key}))


def lightflix():
    base = "https://lightflix.app"
    opener = build_opener(HTTPCookieProcessor(CookieJar())).open
    headers = {
        "Origin": base, "Referer": base + "/en", "Accept": "application/json",
        "Sec-Fetch-Site": "same-origin", "Sec-Fetch-Mode": "cors", "Sec-Fetch-Dest": "empty",
        "Content-Type": "application/json",
    }
    read(base + "/en", opener=opener)
    headers["x-api-key"] = data(base + "/api/tmdb-key", headers=headers, data=b"{}", opener=opener)["key"]
    for path, field in (
        ("/trending/movie/week", "results"), ("/search/multi?query=sherlock", "results"), ("/tv/60694/season/1", "episodes")
    ):
        assert data(base + "/api/tmdb" + path, headers=headers, opener=opener)[field]
    assert "Night of the Living Dead" in text("https://velarium.cc/en/watch/movie/10331?autoPlay=true")
    print("PASS Lightflix catalog, search, seasons and player page. Android playback unverified.")


def nexa():
    base = "https://nexa.mov"
    entry = re.search(r'''["'](?:\./|/)(_app/immutable/entry/app\.[A-Za-z0-9_-]+\.js)["']''', text(base + "/"))[1]
    chunk = re.search(r'''from["']\.\./chunks/([A-Za-z0-9_-]+\.js)["']''', text(base + "/" + entry))[1]
    client = text(base + "/_app/immutable/chunks/" + chunk).split("https://api.themoviedb.org/3", 1)[1]
    key = re.search(r"function [A-Za-z_$][\w$]*\(\)\{return\x60([0-9a-f]{32})\x60\}async function", client)[1]
    assert data("https://backend.nexa.mov/v1/home", headers={"Origin": base, "Referer": base + "/"})["rails"]
    assert tmdb("/search/multi?query=night&include_adult=false", key)["results"]
    assert tmdb("/movie/10331", key)["id"] == 10331
    assert tmdb("/tv/1399/season/1", key)["episodes"]
    assert data(base + "/watch/tmdb-tv/1399/s1-e1/__data.json")["nodes"][-1]["type"] == "data"
    print("PASS Nexa home, search, metadata, seasons and TV player route. Android playback unverified.")


def moonflix():
    base = "https://moonflix.website"
    bundle = re.search(r'''<script[^>]+src=["'](/assets/index-[A-Za-z0-9_-]+\.js)["']''', text(base + "/browse"))[1]
    key = re.search(r'''["']([0-9a-f]{32})["']\.trim\(\)\?\?["']{2},[A-Za-z0-9_$]+=["']https://api\.themoviedb\.org/3["']''', text(base + bundle))[1]
    assert tmdb("/trending/movie/day", key)["results"]
    assert tmdb("/search/multi?query=night&include_adult=false", key)["results"]
    assert tmdb("/tv/1399/season/1", key)["episodes"]
    player = "https://player.moonflix.website"
    source = data("https://vidzy-wlqs.onrender.com/api/stream?type=movie&tmdb=3078",
                  headers={"Origin": player, "Referer": player + "/"})
    url = source["m3u8"]
    origin = "https://player.vidzee.wtf"
    for _ in range(3):
        body, url = read(url, headers={"Origin": origin, "Referer": origin + "/"}, limit=65_536)
        if not body.startswith(b"#EXTM3U"):
            break
        link = next(line.strip() for line in body.decode().splitlines() if line.strip() and not line.startswith("#"))
        url = urljoin(url, link)
    if body.startswith(b"\x89PNG\r\n\x1a\n"):
        offset = 8
        while offset + 12 <= len(body):
            length, tag = struct.unpack_from(">I4s", body, offset)
            start = offset + 8
            if tag == b"tsDa":
                assert length >= 564 and all(body[start + i * 188] == 0x47 for i in range(3))
                break
            offset += 12 + length
        else:
            raise AssertionError("No MPEG-TS payload in PNG prefix")
    else:
        assert len(body) >= 564 and all(body[i * 188] == 0x47 for i in range(3))
    print("PASS Moonflix catalog, search, seasons and direct HLS MPEG-TS media prefix")


failed = False
for check in (lightflix, nexa, moonflix):
    try:
        check()
    except Exception as error:
        # Avoid logging request URLs with temporary catalog and playback parameters.
        print("FAIL", check.__name__, type(error).__name__, getattr(error, "code", ""))
        failed = True
raise SystemExit(failed)
