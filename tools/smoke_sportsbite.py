"""Sportsbite sports/TV smoke test. Saves no media; reads at most 64 KiB per segment."""
import base64
import json
import re
import urllib.parse
import urllib.request

BASE = "https://sportsbite.org"

def get(url, referer=BASE + "/", limit=2_000_000, headers=None):
    req = urllib.request.Request(url, headers={"Referer": referer, "User-Agent": "Mozilla/5.0", **(headers or {})})
    with urllib.request.urlopen(req, timeout=12) as response:
        return response.read(limit)

def route(embed):
    u = urllib.parse.urlsplit(embed)
    if u.hostname != "sportsbite.org":
        return None
    m = re.fullmatch(r"/(247|t247|cv|mx|tlive|lol|dk)-embed/embed/([A-Za-z0-9_-]+)", u.path)
    if m:
        return f"{BASE}/{m[1]}/{m[2]}" + ("?v=high" if m[1] in ("mx", "tlive", "lol", "dk") else "")
    m = re.fullmatch(r"/selfhost/(?:embed|e)/(s-[A-Za-z0-9_-]+)", u.path)
    return f"{BASE}/selfhost/play/{m[1]}?raw=1" if m else None

def playlist(url, referer):
    text = get(url, referer).decode("utf-8-sig").strip()
    assert text.startswith("#EXTM3U"), "Player returned HTML or no HLS"
    return text

def verify(stream):
    embed = stream.get("manifest_url") or stream.get("iframe_url") or stream.get("embed_url")
    source = route(embed)
    if not source:
        html = get(embed).decode()
        m = re.search(r'(?:var|let|const)\s+source\s*=\s*(?:window\.)?atob\(["\']([^"\']+)["\']\)', html)
        assert m, "Unsupported embed"
        source = urllib.parse.urljoin(BASE, base64.b64decode(m[1]).decode())
    text = playlist(source, embed)
    for _ in range(3):
        if "#EXTINF:" in text:
            break
        entry = next(line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#"))
        source = urllib.parse.urljoin(source, entry)
        text = playlist(source, embed)
    assert "#EXTINF:" in text, "No media playlist"
    segment = next(line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#"))
    segment = urllib.parse.urljoin(source, segment)
    # Check only a bounded prefix; image/pixel transports get full synthetic coverage in Kotlin tests.
    prefix = get(segment, "" if urllib.parse.urlsplit(segment).hostname != "sportsbite.org" else embed,
                 65536, {"Range": "bytes=0-65535"})
    assert len(prefix) >= 564, "Empty segment"
    ts = any(prefix[i] == prefix[i+188] == prefix[i+376] == 0x47 for i in range(len(prefix)-376))
    png = prefix.startswith(b"\x89PNG\r\n\x1a\n")
    assert ts or png or prefix.startswith(b"RIFF"), "Unknown segment container"

def check(label, streams):
    failures = []
    for stream in streams[:4]:
        try:
            verify(stream)
            print("PASS", label, "HLS master/media and bounded segment transport")
            return
        except Exception as error:
            failures.append(type(error).__name__)
    raise RuntimeError(label + ": no verified stream: " + ", ".join(failures))

def main():
    body = json.loads(get(BASE + "/api/forestgump/matches"))
    events = [event for day in body["days"] for event in day["events"]]
    assert events and any(event.get("event_key") or event.get("slug") for event in events)
    channels = json.loads(get(BASE + "/api/forestgump/channels"))["channels"]
    assert channels and any(c.get("category") != "Sports" for c in channels)
    assert len({c.get("country") for c in channels}) > 1
    print("PASS sports schedule and TV category/country catalog:", len(events), "events,", len(channels), "channels")
    live = [s for e in events if e.get("live_status", {}).get("state") == "in" or
            (e.get("live") and not e.get("is_finished")) for s in e.get("streams", []) if s.get("status") == "online"]
    # Separate families exercise the normal selfhost route and the non-sports TV transports.
    if live:
        check("live sports", sorted(live, key=lambda s: "selfhost/embed/" not in s.get("manifest_url", "")))
    else:
        print("SKIP sports playback: no live event currently listed")
    for family in ("247-", "t247-", "cv-"):
        candidates = [c for c in channels if c["id"].startswith(family) and c.get("category") != "Sports"
                      and c.get("status") == "online" and c.get("browser_playable") is not False]
        assert candidates, "No non-sports channels for " + family
        check("non-sports TV " + family, candidates)

if __name__ == "__main__":
    main()
