"""Movy normal public-client protocol check using Night of the Living Dead (1968).
Reads HLS and a bounded media prefix in memory; never saves movies or keys.
"""
import base64
import json
import urllib.parse
import urllib.request

BASE = "https://www.movy.sx"
DB = "https://db.wecollege.net/3"
API = "https://api.wecollege.net"
HEADERS = {"User-Agent": "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
           "Origin": BASE, "Referer": BASE + "/", "Accept": "application/json"}
MASK = 0xffffffff
def mix(x):
    x &= MASK
    x = ((x ^ (x >> 16)) * 0x85ebca6b) & MASK
    x = ((x ^ (x >> 13)) * 0xc2b2ae35) & MASK
    return (x ^ (x >> 16)) & MASK
def rotate(x, n):
    x &= MASK
    n &= 31
    return ((x << n) | (x >> (32-n))) & MASK
def decode(payload, seed, media_id):
    data = bytearray(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    h = 0x811c9dc5
    raw = seed.encode("utf-16-le")
    for i in range(0, len(raw), 2):
        h = ((h ^ int.from_bytes(raw[i:i+2], "little")) * 0x01000193) & MASK
    n = mix(mix(h) ^ mix(media_id ^ 0x9e3779b9))
    state = {}
    for i in range(8):
        index = n % 61
        n = rotate((n + 0x9e3779b9) & MASK, 7 + (i & 7))
        state[index] = n ^ mix(n)
        n = mix(n + index)
    acc = mix(0xa5a5a5a5 ^ n)
    for counter, at in enumerate(range(0, len(data), 4)):
        index = acc % 61
        s = state.get(index, 0) ^ ((0x9e3779b9 * (counter+1)) & MASK)
        b = (acc ^ s) | (acc & s if index in state else 0)
        acc = mix((rotate(b + acc, index) ^ rotate(acc, index*7)) + 0x9e3779b9)
        state[index] = acc
        for offset in range(min(4, len(data)-at)):
            data[at+offset] ^= (acc >> (offset*8)) & 255
    assert data[:4] == b"mvm1", "Public source response format changed"
    return json.loads(data[4:])
def get(url, limit=2_000_000, headers=None):
    req = urllib.request.Request(url, headers={**HEADERS, **(headers or {})})
    with urllib.request.urlopen(req, timeout=25) as response:
        return response.read(limit)
def obj(url):
    return json.loads(get(url))
def main():
    movie = obj(DB + "/movie/10331?append_to_response=external_ids&language=en-US")
    assert movie["title"] == "Night of the Living Dead"
    one = obj(DB + "/movie/popular?page=1")
    two = obj(DB + "/movie/popular?page=2")
    assert one["results"][0]["id"] != two["results"][0]["id"]
    assert obj(DB + "/search/multi?query=night%20of%20the%20living%20dead")["results"]
    assert obj(DB + "/tv/1396")["seasons"]
    assert obj(DB + "/tv/1396/season/1")["episodes"]
    print("PASS movie catalog/pagination/search, TV details/seasons/episodes")
    failures = []
    for server in ("miami", "boise", "seattle", "chicago"):
        try:
            seed = obj(API + "/seed?mediaId=10331")["seed"]
            params = {"title": urllib.parse.quote(movie["title"], safe=""), "mediaType": "movie", "year": "1968",
                      "tmdbId": "10331", "imdbId": movie["imdb_id"], "totalSeasons": "0",
                      "seasonId": "1", "episodeId": "1", "enc": "2", "seed": seed}
            encoded = get(API + "/" + server + "/sources?" + urllib.parse.urlencode(params)).decode().strip()
            if encoded.startswith('"'):
                encoded = json.loads(encoded)
            body = decode(encoded, seed, 10331)
            for source in body.get("sources", [])[:2]:
                url = source["url"]
                text = get(url).decode("utf-8-sig").strip()
                if not text.startswith("#EXTM3U"):
                    continue
                for _ in range(3):
                    if "#EXTINF:" in text:
                        break
                    entry = next(line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#"))
                    url = urllib.parse.urljoin(url, entry)
                    text = get(url).decode("utf-8-sig").strip()
                assert "#EXTINF:" in text
                entry = next(line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#"))
                prefix = get(urllib.parse.urljoin(url, entry), 65536, {"Range": "bytes=0-65535"})
                assert len(prefix) >= 188
                print("PASS public seed, source metadata decoding, HLS and bounded media prefix (" + server + ")")
                return
        except Exception as e:
            failures.append(type(e).__name__)
    raise RuntimeError("No verified current source: " + ", ".join(failures))
if __name__ == "__main__":
    main()
