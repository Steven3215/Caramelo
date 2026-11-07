"""Verify SoloLatino catalog, episode lists and anonymous embed resolution."""

import json
import re
import time
from http.cookiejar import CookieJar
from urllib.parse import unquote, urlsplit
from urllib.request import HTTPCookieProcessor, Request, build_opener


BASE = "https://sololatino.net"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")


def smoke():
    jar = CookieJar()
    opener = build_opener(HTTPCookieProcessor(jar))

    def get(path, headers=None, data=None):
        request = Request(BASE + path, headers={"User-Agent": UA, **(headers or {})}, data=data)
        with opener.open(request, timeout=18) as result:
            return result.read(3_000_000).decode("utf-8")

    assert "card__poster" in get("/peliculas?page=1")
    assert "/serie/tokyo-revengers" in get("/buscar?q=tokyo")
    series = get("/serie/tokyo-revengers")
    assert "/temporada-1/episodio-1" in series and "ep-item" in series
    route = "/serie/tokyo-revengers/temporada-1/episodio-1"
    headers = {
        "Origin": BASE, "Referer": BASE + route, "Accept": "application/json", "Cache-Control": "no-cache",
        "Sec-Fetch-Site": "same-origin", "Sec-Fetch-Mode": "cors", "Sec-Fetch-Dest": "empty",
    }
    get("/sanctum/csrf-cookie", headers)
    watch = get(route + "?_=" + str(int(time.time())), {"Cache-Control": "no-cache", "Accept": "text/html"})
    token = re.search(r'data-player-token="([^"]+)"', watch)[1]
    csrf = next((cookie.value for cookie in jar if cookie.name == "XSRF-TOKEN"), None)
    if csrf:
        headers["X-XSRF-TOKEN"] = unquote(csrf)
    else:
        headers["X-CSRF-TOKEN"] = re.search(r'<meta name="csrf-token" content="([^"]+)"', watch)[1]
    headers["Content-Type"] = "application/json"
    result = json.loads(get("/api/player-url", headers, json.dumps({"t": token}).encode("utf-8")))
    assert urlsplit(result["url"]).hostname in {"embed69.org", "player.pelisserieshoy.com"}
    print("PASS SoloLatino catalog, search, episodes and anonymous embed. Android playback unverified.")


try:
    smoke()
except Exception as error:
    print("FAIL SoloLatino", type(error).__name__, getattr(error, "code", ""))
    raise SystemExit(1)
