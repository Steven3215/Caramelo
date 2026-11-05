"""Corsflix catalog/embed metadata test; Android WebView playback requires device verification."""
import ast, html, json, re, urllib.parse, urllib.request
BASE="https://watch.corsflix.net"
H={"User-Agent":"Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"}
def get(url,ref=BASE+"/"):
    with urllib.request.urlopen(urllib.request.Request(url,headers={**H,"Referer":ref}),timeout=20) as r:return r.read(2000000).decode("utf-8-sig")
def obj(path):return json.loads(get(BASE+"/api"+path))
def main():
    assert obj("/movie/popular?page=1")["results"]
    assert obj("/search/query?query=night%20of%20the%20living%20dead")["results"]
    assert obj("/movie/10331")["id"]==10331
    assert obj("/tv/1396/season/1")["episodes"]
    doc=get("https://www.2embed.cc/embed/10331")
    frame=html.unescape(re.search(r'data-src="(https://streamsrcs\.2embed\.cc/[^"]+)"',doc)[1])
    second=get(frame,"https://www.2embed.cc/")
    frame_tag=next(tag for tag in re.findall(r'<iframe\b[^>]+>',second,re.S) if 'id="framesrc"' in tag)
    code=re.search(r'src="([A-Za-z0-9]+)"',frame_tag)[1]
    packed=get("https://2vcdn.skin/e/"+code,frame)
    assert "eval(function(p,a,c,k" in packed
    print("PASS Corsflix movie/TV catalog, search and listed embed chain")
    print("UNVERIFIED Corsflix playback: direct CDN returned HTTP403 in manual testing; Android browser fallback needs device testing")
if __name__=="__main__":main()
