"""Live integration checks. Test playback uses Night of the Living Dead (1968).
No API response URLs or temporary stream tokens are printed.
"""
import base64
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://cinema.army"
KEY = b"f16a6b92fcd6eed3e9476297f893f2505c32f79bc6efef9b6c95496aa5914548"
TOKEN_KEY = b"a2cfc9eaa2a3690b5d4f6b5f958dbdeae52a097283d375bd253ee7c7062524f5"

def request(url, method="GET", extra=None, limit=2_000_000):
    headers = {"Referer": BASE + "/", "Origin": BASE, "User-Agent": "Mozilla/5.0",
               "Accept": "application/json", **(extra or {})}
    req = urllib.request.Request(url, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=25) as response:
        return response.read(limit)

def api(path, method="GET", extra=None):
    return json.loads(request(BASE + "/api/" + path, method, extra))

def main():
    popular = api("movies/popular?page=1")
    assert popular["results"], "Empty popular catalog"
    print("PASS movie catalog")
    search = api("search?query=Night%20of%20the%20Living%20Dead")
    assert any(item.get("id") == 10331 for item in search["results"]), "Search lost the test film"
    print("PASS search")
    movie = api("movies/10331")
    assert movie["id"] == 10331 and movie.get("title") and movie.get("poster_path")
    print("PASS movie details")
    shows = api("tv/popular?page=1")
    show_id = shows["results"][0]["id"]
    show = api("tv/" + str(show_id))
    seasons = [s for s in show["seasons"] if s["season_number"] > 0]
    assert seasons, "No TV seasons"
    season = api(f"tv/{show_id}/season/{seasons[0]['season_number']}")
    assert season["episodes"] and season["episodes"][0].get("episode_number")
    print("PASS TV catalog, details, episodes")
    failures = []
    for server in ("cookiebakers", "tcloud", "ipcloud", "dcloud"):
        try:
            token = hmac.new(TOKEN_KEY, f"10331:{int(time.time()) // 60}".encode(), hashlib.sha256).hexdigest()
            result = api(f"{server}/movie/10331", "POST" if server == "cookiebakers" else "GET",
                         {"X-Stream-Token": token})
            value = result["stream"]["url"]
            if not value.startswith(("http://", "https://")):
                raw = base64.b64decode(value, validate=True)
                value = bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(raw)).decode()
            assert urllib.parse.urlsplit(value).scheme in ("http", "https")
            playlist = request(value).decode("utf-8-sig")
            assert playlist.lstrip().startswith("#EXTM3U"), "Not an HLS playlist"
            # Check a media playlist when the returned URL is a master playlist.
            entries = [line.strip() for line in playlist.splitlines()
                       if line.strip() and not line.startswith("#")]
            assert entries, "Empty HLS playlist"
            child = urllib.parse.urljoin(value, entries[0])
            if "#EXT-X-STREAM-INF" in playlist:
                media = request(child).decode("utf-8-sig")
                assert media.lstrip().startswith("#EXTM3U") and "#EXTINF" in media
            else:
                assert "#EXTINF" in playlist
            print(f"PASS playback playlist ({server})")
            return
        except Exception as error:
            # Do not expose signed URLs through exception strings.
            failures.append(f"{server}: {type(error).__name__}")
    raise RuntimeError("No playable test stream; " + ", ".join(failures))

if __name__ == "__main__":
    main()
