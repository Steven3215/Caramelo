"""AnimeSuge anonymous catalog/episode/player check. Saves no media or keys."""
import base64, html, json, re, subprocess, urllib.parse, urllib.request
BASE="https://animesuge.cz"
H={"User-Agent":"Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36","X-Requested-With":"XMLHttpRequest"}
def get(url,ref=BASE+"/",limit=2000000,extra=None):
    with urllib.request.urlopen(urllib.request.Request(url,headers={**H,"Referer":ref,**(extra or {})}),timeout=25) as r:return r.read(limit)
def obj(url,ref=BASE+"/"):return json.loads(get(url,ref))
def decrypt(value):
    data=base64.urlsafe_b64decode(value+"="*(-len(value)%4))
    key=b"i?LMTAx0Q6,:}50U".ljust(32,b"\0");iv=b"W0;27ToaUpl_P%'c".ljust(16,b"\0")
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        from cryptography.hazmat.primitives.padding import PKCS7
        dec=Cipher(algorithms.AES(key),modes.CBC(iv)).decryptor();padded=dec.update(data)+dec.finalize()
        unpad=PKCS7(128).unpadder();return json.loads(unpad.update(padded)+unpad.finalize())
    except ImportError:
        raw=subprocess.run(["openssl","enc","-d","-aes-256-cbc","-K",key.hex(),"-iv",iv.hex()],input=data,capture_output=True,check=True).stdout
        return json.loads(raw)
def main():
    listing=get(BASE+"/latest-updated?page=1").decode()
    assert re.search(r'class="[^"]*\bposter\b',listing)
    detail_url=BASE+"/anime/black-clover-g7tjy"
    detail=get(detail_url).decode()
    anime_id=re.search(r'watch-wrap[^>]*data-id="(\d+)"',detail,re.S)[1]
    episodes=obj(BASE+"/ajax/episode/list/"+anime_id,detail_url)["result"]
    ids=html.unescape(re.search(r'data-ids="([^"]+)"',episodes)[1])
    servers=obj(BASE+"/ajax/server/list?servers="+urllib.parse.quote(ids,safe=""),detail_url)["result"]
    links=re.findall(r'data-link-id="([^"]+)"',servers)
    assert links
    print("PASS AnimeSuge listing/details/episode and server APIs")
    failures=[]
    for link in links[:2]:
        try:
            embed=obj(BASE+"/ajax/server?get="+urllib.parse.quote(html.unescape(link),safe=""),detail_url)["result"]["url"]
            assert urllib.parse.urlsplit(embed).hostname=="megaplay.buzz"
            player=get(embed,detail_url).decode()
            media_id=re.search(r'id="megaplay-player"[^>]*data-id="(\d+)"',player,re.S)[1]
            bundle=obj("https://megaplay.buzz/stream/getSourcesNew?id="+media_id+("&s=tcdn" if "s=tcdn" in embed else ""),embed)
            source=decrypt(bundle["enc"])["file"]
            text=get(source,"https://megaplay.buzz/").decode("utf-8-sig").strip()
            assert text.startswith("#EXTM3U")
            for _ in range(3):
                if "#EXTINF:" in text:break
                line=next(x.strip() for x in text.splitlines() if x.strip() and not x.startswith("#"))
                source=urllib.parse.urljoin(source,line);text=get(source,"https://megaplay.buzz/").decode("utf-8-sig").strip()
            assert "#EXTINF:" in text
            line=next(x.strip() for x in text.splitlines() if x.strip() and not x.startswith("#"))
            prefix=get(urllib.parse.urljoin(source,line),"",65536,{"Range":"bytes=0-65535"})
            assert len(prefix)>=188
            print("PASS AnimeSuge metadata transport, HLS and bounded media prefix; captions:",len(bundle.get("tracks",[])))
            return
        except Exception as e:failures.append(type(e).__name__)
    raise RuntimeError("No HLS verified: "+",".join(failures))
if __name__=="__main__":main()
