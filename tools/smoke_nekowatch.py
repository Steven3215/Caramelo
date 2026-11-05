"""Nekowatch anonymous catalog/episode/HLS smoke check; bounded in-memory media prefix."""
import json, urllib.parse, urllib.request
BASE="https://nekowatch.xyz"
H={"User-Agent":"Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36","X-Requested-With":"NekoWatch","Referer":BASE+"/"}
def get(url,limit=2000000,extra=None):
    with urllib.request.urlopen(urllib.request.Request(urllib.parse.urljoin(BASE,url),headers={**H,**(extra or {})}),timeout=25) as r:return r.read(limit)
def obj(path):return json.loads(get(path))
def main():
    home=obj("/api/home?v=2")
    assert home["trending"] and home["popular"]
    assert obj("/api/anime/search?q=sword%20art%20online&complete=1")["items"]
    detail=obj('/api/anilist?q=33a449e9458c7689b90abecdc3fa9560&op=NekoWatchAnimeDetails&v=%7B%22id%22%3A11757%7D&opName=details-core')
    assert detail["data"]["Media"]["id"]==11757
    eps=obj("/api/anime/episodes/konbanwa/arigato/konichiwa/11757?map=false")
    assert eps["reanime"]["episodes"]["sub"]
    print("PASS Nekowatch home/search/details and SUB/DUB episode API")
    failures=[]
    for provider,alias in (("reanime","konbanwa"),("animegg","arigato"),("anikoto","konichiwa")):
        try:
            sub=eps.get(provider,{}).get("episodes",{}).get("sub",[])
            if not sub:continue
            ep=sub[0]
            tail=ep["id"].split("/")[-1].removeprefix(provider+"-")
            play=obj(f"/api/anime/watch/{alias}/11757/sub/{alias}-{tail}")
            assert play.get("animeId",11757)==11757 and play.get("episode",ep["number"])==ep["number"] and play.get("audio","sub")=="sub"
            for stream in play.get("streams",[]):
                if stream.get("kind")!="hls":continue
                url=urllib.parse.urljoin(BASE,stream["url"]);text=get(url).decode("utf-8-sig").strip()
                assert text.startswith("#EXTM3U")
                for _ in range(3):
                    if "#EXTINF:" in text:break
                    line=next(x.strip() for x in text.splitlines() if x.strip() and not x.startswith("#"))
                    url=urllib.parse.urljoin(url,line);text=get(url).decode("utf-8-sig").strip()
                assert "#EXTINF:" in text
                line=next(x.strip() for x in text.splitlines() if x.strip() and not x.startswith("#"))
                prefix=get(urllib.parse.urljoin(url,line),65536,{"Range":"bytes=0-65535"})
                assert len(prefix)>=188
                print("PASS Nekowatch signed HLS and bounded media prefix")
                return
        except Exception as e:failures.append(type(e).__name__)
    raise RuntimeError("No HLS verified: "+",".join(failures))
if __name__=="__main__":main()
