"""Real CPU inference with the exact shipped weights; NOT Android/device proof."""
import base64,io,json,pathlib,subprocess,time,urllib.request,socket,sys
import numpy as np
from PIL import Image,ImageDraw
from ai_edge_litert.interpreter import Interpreter
import jsonschema
root=pathlib.Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets';models=assets/'models'
out=root/'verification';out.mkdir(exist_ok=True)
result={'platform':'GitHub Actions Ubuntu x86_64 CPU','physical_device_tested':False,'models':{}}
red=Image.new('RGB',(384,384),'white');ImageDraw.Draw(red).ellipse((80,80,304,304),fill='red')
blue=Image.new('RGB',(384,384),'black');ImageDraw.Draw(blue).rectangle((70,140,314,244),fill='blue')
images=[red,blue]
for name in ['nima_aesthetic.tflite','nima_technical.tflite']:
 it=Interpreter(model_path=str(models/name),num_threads=2);it.allocate_tensors();scores=[]
 for im in images:
  data=(np.asarray(im.resize((224,224)),dtype=np.float32)/127.5-1)[None]
  it.set_tensor(it.get_input_details()[0]['index'],data);it.invoke()
  p=it.get_tensor(it.get_output_details()[0]['index']).reshape(-1)
  assert p.shape==(10,) and np.isfinite(p).all() and np.all(p>=0) and abs(p.sum()-1)<.02
  scores.append(float(np.arange(1,11)@p))
 assert abs(scores[0]-scores[1])>1e-6,'Model output is invariant to test inputs'
 result['models'][name]={'scores':scores,'status':'actual inference passed'}
def start(name,vision):
 with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
 args=[str(root/'vendor/host-build/bin/llama-server'),'-m',str(models/name),'--host','127.0.0.1','--port',str(port),'--api-key','ci-only','-c','6144','-t','3','-ngl','0','-np','1','--no-warmup','--reasoning','off','--no-webui']
 if vision:args+=['--mmproj',str(models/'mmproj.gguf'),'--no-mmproj-offload']
 log=(out/(name+'.log')).open('w');p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT)
 try:
  for _ in range(180):
   if p.poll() is not None:raise RuntimeError('Server exited: '+name)
   try:
    with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}/health',headers={'Authorization':'Bearer ci-only'}),timeout=1) as resp:
     if resp.status==200:return p,log,port
   except OSError:time.sleep(1)
  raise TimeoutError('Loading '+name)
 except BaseException:p.terminate();p.wait(timeout=10);log.close();raise

def generate(port,prompt,schema,max_tokens,image=None):
 content=prompt
 if image:
  buf=io.BytesIO();image.save(buf,format='JPEG')
  content=[{'type':'image_url','image_url':{'url':'data:image/jpeg;base64,'+base64.b64encode(buf.getvalue()).decode()}},{'type':'text','text':prompt}]
 body={'messages':[{'role':'user','content':content}],'seed':42,'temperature':.1,'max_tokens':max_tokens,'stream':False,'chat_template_kwargs':{'enable_thinking':False},'response_format':{'type':'json_object','schema':schema}}
 req=urllib.request.Request(f'http://127.0.0.1:{port}/v1/chat/completions',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer ci-only'})
 with urllib.request.urlopen(req,timeout=600) as resp:data=json.load(resp)
 choice=data['choices'][0];assert choice['finish_reason']!='length',choice
 obj=json.loads(choice['message']['content']);jsonschema.validate(obj,schema)
 return obj
p,log,port=start('vision.gguf',True)
try:
 started=time.time();schema=json.loads((assets/'vision_schema.json').read_text());prompt=(assets/'vision_prompt.txt').read_text()
 scenes=[generate(port,prompt,schema,1400,im) for im in images]
 assert scenes[0]['description']!=scenes[1]['description'],'Scene descriptions are invariant'
 result['models']['SmolVLM2']={'seconds':time.time()-started,'outputs':scenes,'status':'actual image + projector inference passed; semantic quality requires human review'}
finally:p.terminate();p.wait(timeout=20);log.close()
p,log,port=start('korean.gguf',False)
try:
 started=time.time();schema=json.loads((assets/'report_schema.json').read_text())
 prompt='/no_think\n주어진 사진 분석만 근거로 한국어 비평을 JSON으로 작성하세요. 각 필드는 짧은 한 문장. observations는 관찰, interpretation은 해석, strengths 강점, improvements 개선점, composition 구도, exposure 노출, color 색채, sharpness 선명도, shooting 촬영, editing 보정, summary 종합입니다. 모르는 정보는 불확실하다고 쓰세요. DATA: '+json.dumps(scenes[0],ensure_ascii=False)
 report=generate(port,prompt,schema,1000)
 assert sum('가'<=c<='힣' for c in json.dumps(report,ensure_ascii=False))>=20,'Korean generation check failed'
 result['models']['Qwen3']={'seconds':time.time()-started,'output':report,'status':'actual Korean text generation passed; not a quality benchmark'}
finally:p.terminate();p.wait(timeout=20);log.close()
(out/'inference-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps({k:v.get('status') for k,v in result['models'].items()},indent=2))
