"""CI-only downloads. App runtime never downloads weights."""
import hashlib,json,pathlib,urllib.request,time
ROOT=pathlib.Path(__file__).resolve().parents[1]
TARGET=ROOT/'app/src/main/assets/models'
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
 return h.hexdigest()
def main():
 for m in json.loads((TARGET/'manifest.json').read_text()):
  p=TARGET/m['name']
  if p.exists() and p.stat().st_size==m['size'] and digest(p)==m['sha256']: continue
  print('Downloading',m['name'],flush=True)
  for attempt in range(4):
   try:
    tmp=p.with_suffix('.part')
    with urllib.request.urlopen(m['url'],timeout=120) as src,tmp.open('wb') as dst:
     while chunk:=src.read(1024*1024): dst.write(chunk)
    assert tmp.stat().st_size==m['size'],f'Size mismatch: {p}'
    assert digest(tmp)==m['sha256'],f'SHA mismatch: {p}'
    tmp.replace(p);break
   except Exception:
    if attempt==3: raise
    time.sleep(3*(attempt+1))
 print('All six weight files verified',flush=True)
if __name__=='__main__': main()
