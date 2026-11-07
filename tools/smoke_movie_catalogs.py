"""Check Lightflix catalog, search, seasons, and the player page."""

import json
from http.cookiejar import CookieJar
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
    print("PASS Lightflix catalog, search, seasons and player page.")


try:
    lightflix()
except Exception as error:
    # Avoid logging URLs with temporary catalog and playback parameters.
    print("FAIL Lightflix", type(error).__name__, getattr(error, "code", ""))
    raise SystemExit(1)
