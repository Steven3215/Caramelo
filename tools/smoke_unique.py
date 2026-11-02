"""Read-only UniqueStream API/HLS compatibility checks. No video segments or key material are saved."""
import json
import urllib.parse
import urllib.request

BASE = "https://anime.uniquestream.net"
HEADERS = {"Referer": BASE + "/", "User-Agent": "Mozilla/5.0"}

def request(url, extra=None):
    req = urllib.request.Request(url, headers={**HEADERS, **(extra or {})})
    with urllib.request.urlopen(req, timeout=25) as response:
        return response.read(2_000_000)

def api(path):
    return json.loads(request(BASE + "/api/v1/" + path))

def main():
    page = api("videos/popular?page=1&limit=20")
    assert isinstance(page, list) and page and page[0].get("content_id")
    second = api("videos/popular?page=2&limit=20")
    assert second and page[0]["content_id"] != second[0]["content_id"], "Catalog pagination repeated"
    print("PASS catalog and pagination")
    search = api("search?query=Kaiju&t=all&limit=20")
    assert any(x["content_id"] == "NzCTSBgz" for x in search["series"])
    print("PASS search")
    series = api("series/NzCTSBgz")
    assert series["title"] and series["seasons"]
    episodes = api(f"season/{series['seasons'][0]['content_id']}/episodes?page=1&limit=20&order_by=asc")
    assert episodes and episodes[0]["content_id"]
    episode = api("content/" + episodes[0]["content_id"])
    assert episode["content_type"] == "episode"
    movies = api("videos/movies?page=1&limit=1")
    movie = api("movie/" + movies[0]["content_id"])
    assert movie["title"] and movie["images"]
    print("PASS series, season, episode and movie details")
    media = api("episode/" + episode["content_id"] + "/media/hls/ja-JP")
    assert media.get("hls"), "No ready HLS response"
    playlist_url = media["hls"]["playlist"]
    playlist = request(playlist_url).decode("utf-8-sig")
    assert playlist.lstrip().startswith("#EXTM3U")
    entries = [line.strip() for line in playlist.splitlines() if line.strip() and not line.startswith("#")]
    assert entries
    child = urllib.parse.urljoin(playlist_url, entries[0])
    variant = request(child).decode("utf-8-sig")
    assert variant.startswith("#EXTM3U") and "#EXTINF" in variant
    # Verify subtitle content, without downloading any video/audio segments or HLS keys.
    subs = media["hls"].get("subtitles", [])
    assert subs
    subtitle = request(subs[0]["url"]).decode("utf-8-sig")
    assert "WEBVTT" in subtitle[:100]
    print("PASS signed HLS master/media playlists and subtitles")

if __name__ == "__main__":
    main()
