"""Public Global Free TV catalog/HLS checks; bounded in-memory media prefixes only."""
import json
import urllib.parse
import urllib.request

API = "https://api.globalfreetv.com"
UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
def get(url, headers=None, limit=2_000_000):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=12) as r:
        return r.read(limit)
def api(path):
    return json.loads(get(API + path, {"Accept": "application/json"}))
def main():
    categories = api("/categories")["data"]
    countries = api("/countries")["data"]
    counts = api("/countries-with-channel-count")["data"]
    assert categories and countries and counts
    one = api("/channels?page=1&pageSize=2&orderBy=name&order=ASC")
    two = api("/channels?page=2&pageSize=2&orderBy=name&order=ASC")
    assert one["data"] and two["data"] and one["data"][0]["id"] != two["data"][0]["id"]
    assert api("/categories/news/channels?pageSize=2")["data"]
    assert all(c["country"] == "GB" for c in api("/countries/GB/channels?pageSize=2")["data"])
    print("PASS catalog, categories, countries and pagination")
    failures = []
    for query in ("NHK World", "DW English", "France 24"):
        found = api("/search?query=" + urllib.parse.quote(query) + "&pageSize=3")
        for channel in found["data"][:2]:
            detail = api("/channels/" + urllib.parse.quote(channel["id"], safe=""))
            assert detail["id"] == channel["id"]
            for stream in api("/channels/" + urllib.parse.quote(channel["id"], safe="") + "/streams")["data"][:4]:
                url = stream["url"]
                if "youtu" in urllib.parse.urlsplit(url).hostname:
                    continue
                try:
                    headers = {"Referer": stream.get("http_referrer") or "",
                               "User-Agent": stream.get("user_agent") or UA}
                    text = get(url, headers).decode("utf-8-sig").strip()
                    assert text.startswith("#EXTM3U")
                    for _ in range(3):
                        if "#EXTINF:" in text:
                            break
                        entry = next(x.strip() for x in text.splitlines() if x.strip() and not x.startswith("#"))
                        url = urllib.parse.urljoin(url, entry)
                        text = get(url, headers).decode("utf-8-sig").strip()
                    assert "#EXTINF:" in text
                    segment = next(x.strip() for x in text.splitlines() if x.strip() and not x.startswith("#"))
                    prefix = get(urllib.parse.urljoin(url, segment), {**headers, "Range": "bytes=0-65535"}, 65536)
                    assert len(prefix) >= 564
                    ts = any(prefix[i] == prefix[i+188] == prefix[i+376] == 0x47 for i in range(len(prefix)-376))
                    assert ts or b"ftyp" in prefix[:32] or b"styp" in prefix[:32] or b"moof" in prefix[:128]
                    print("PASS search, channel details, broadcaster HLS and bounded media prefix")
                    return
                except Exception as e:
                    failures.append(type(e).__name__)
    raise RuntimeError("No broadcaster stream verified: " + ", ".join(failures))
if __name__ == "__main__":
    main()
