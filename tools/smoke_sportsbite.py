"""Sportsbite live catalog/player check. Fetches at most a 64 KiB segment prefix; saves no media."""
import base64
import json
import re
import urllib.parse
import urllib.request

BASE = "https://sportsbite.org"
def get(url, referer=BASE + "/", limit=2_000_000, headers=None):
    req = urllib.request.Request(url, headers={"Referer": referer, "User-Agent": "Mozilla/5.0", **(headers or {})})
    with urllib.request.urlopen(req, timeout=20) as response:
        return response.read(limit)

def main():
    body = json.loads(get(BASE + "/api/forestgump/matches"))
    events = [event for day in body["days"] for event in day["events"]]
    assert events and any(event.get("event_key") or event.get("slug") for event in events)
    print("PASS live sports schedule")
    candidates = [(event, stream) for event in events
                  for stream in event.get("streams", [])
                  if stream.get("status") == "online"
                  and urllib.parse.urlsplit(stream.get("manifest_url", "")).hostname == "sportsbite.org"
                  and "-embed/embed/" in stream.get("manifest_url", "")]
    failures = []
    for event, stream in candidates[:8]:
        try:
            embed = stream["manifest_url"]
            html = get(embed).decode()
            match = re.search(r'var\s+source\s*=\s*window.atob\("([^"]+)"\)', html)
            assert match, "No supported player source"
            source = base64.b64decode(match[1]).decode()
            playlist = get(source, embed).decode("utf-8-sig")
            assert playlist.startswith("#EXTM3U")
            if "#EXTINF" not in playlist:
                entry = next(line.strip() for line in playlist.splitlines() if line.strip() and not line.startswith("#"))
                source = urllib.parse.urljoin(source, entry)
                playlist = get(source, embed).decode("utf-8-sig")
            assert "#EXTINF" in playlist
            segment = next(line.strip() for line in playlist.splitlines() if line.strip() and not line.startswith("#"))
            prefix = get(urllib.parse.urljoin(source, segment), "", 65536, {"Range": "bytes=0-65535"})
            sync = any(prefix[i] == prefix[i+188] == prefix[i+376] == 0x47 for i in range(max(0, len(prefix)-376)))
            assert sync, "No MPEG-TS sync in transport prefix"
            print("PASS listed player, live HLS playlist and media transport")
            return
        except Exception as error:
            failures.append(type(error).__name__)
    raise RuntimeError("No verified current stream: " + ", ".join(failures))

if __name__ == "__main__":
    main()
