"""Check the two TV directories and representative public RTVE HLS signals."""

import base64
import json
import re
from urllib.parse import urljoin
from urllib.request import Request, urlopen


def read(url, size=3_000_000):
    with urlopen(Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=15) as result:
        return result.read(size)


def playlist(url):
    body = read(url).decode("utf-8-sig")
    assert body.startswith("#EXTM3U"), "Expected an HLS playlist"
    items = [line.strip() for line in body.splitlines() if line.strip() and not line.startswith("#")]
    assert items, "No entries in HLS playlist"
    return body, items


def check_media(url):
    body, items = playlist(url)
    if "#EXT-X-STREAM-INF" in body:
        url = urljoin(url, items[0])
        _, items = playlist(url)
    assert read(urljoin(url, items[0]), 65_536), "Empty media segment"


online = "https://teleonline.org"
posts = json.loads(read(online + "/wp-json/wp/v2/posts?categories=24&categories_exclude=130&per_page=5"))
assert len(posts) == 5 and all("/canal/" in item["link"] for item in posts)
search = json.loads(read(online + "/wp-json/wp/v2/posts?search=La%201&per_page=5"))
assert any(item["link"].endswith("/la-1/") for item in search)
print("PASS Teleonline country catalog and search")
channel = read(online + "/canal/24-horas/").decode()
links = [base64.b64decode(x).decode("utf-8", "replace") for x in re.findall(r"atob\('([A-Za-z0-9+/=]+)'\)", channel)]
for url in links:
    if url.startswith("https://") and url.endswith(".m3u8"):
        try:
            check_media(url)
            break
        except (OSError, AssertionError):
            continue
else:
    raise AssertionError("No working 24h HLS media sample")
print("PASS Teleonline 24h public HLS and media prefix")

for domain in ("telelibre.st", "tele-libre.top"):
    base = "https://" + domain
    directory = read(base + "/").decode()
    assert "data-category=\"noticias\"" in directory and "/en-vivo/canal-24-horas/" in directory
    detail = read(base + "/en-vivo/canal-24-horas/").decode()
    assert "/en-vivo/canal-24-horas/embed.php" in detail
    embed = read(base + "/en-vivo/canal-24-horas/embed.php").decode()
    frame = re.search(r'<iframe[^>]+src="(https://www\.rtve\.es/play/videos/directo/canales-lineales/[^"]+)"', embed)
    assert frame, "Expected a public RTVE broadcaster iframe"
    print(f"PASS {domain} directory and Canal 24 Horas embed")
rtve = read(frame[1]).decode()
identifier = re.search(r'<meta name="DC.identifier" content="(\d+)"', rtve)
assert identifier, "RTVE live identifier unavailable"
check_media("https://ztnr.rtve.es/ztnr/" + identifier[1] + ".m3u8")
print("PASS Tele Libre Canal 24 Horas public HLS and media prefix")
