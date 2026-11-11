import hashlib,json,pathlib,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
manifest=json.loads((root/'app/src/main/assets/models/manifest.json').read_text())
with zipfile.ZipFile(apk) as z:
 for m in manifest:
  name='assets/models/'+m['name'];info=z.getinfo(name)
  assert info.file_size==m['size'],name
  assert info.compress_type==zipfile.ZIP_STORED,name
  h=hashlib.sha256()
  with z.open(name) as f:
   while b:=f.read(1024*1024):h.update(b)
  assert h.hexdigest()==m['sha256'],name
 assert z.getinfo('lib/arm64-v8a/libframecritic-server.so').file_size>1_000_000
 assert not any('/x86' in n for n in z.namelist())
report={'apk':apk.name,'bytes':apk.stat().st_size,'sha256':hashlib.file_digest(apk.open('rb'),'sha256').hexdigest(),'embedded_models_verified':len(manifest),'device_execution':'NOT RUN: no physical Galaxy S25 Ultra attached'}
(root/'verification').mkdir(exist_ok=True)
(root/'verification/apk-verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
