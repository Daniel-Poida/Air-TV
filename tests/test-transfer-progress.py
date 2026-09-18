"""Scoped slow synthetic transfers on .31. Tests TV progress, cancellation, disconnect cleanup and batch totals."""
import concurrent.futures,hashlib,http.client,json,pathlib,re,subprocess,time,xml.etree.ElementTree as ET
HOST='192.168.0.31';SERIAL=HOST+':5555';ADB=pathlib.Path(__file__).resolve().parent/'run-adb-airtv.sh';PREFIX='AirTV-progress-test-'
def adb(command,binary=False):
 r=subprocess.run([str(ADB),'-s',SERIAL,'exec-out' if binary else 'shell',command],capture_output=True,check=True)
 return r.stdout if binary else r.stdout.decode()
def snapshot():
 adb('uiautomator dump /sdcard/airtv-progress-test-ui.xml');raw=adb('cat /sdcard/airtv-progress-test-ui.xml');return ET.fromstring(raw[raw.index('<?xml'):])
def click(root,label):
 matches=[n for n in root.iter('node') if n.get('text','').casefold()==label.casefold() and n.get('package')=='dev.airtv.receiver']
 if len(matches)!=1:raise AssertionError('Control not uniquely observed: '+label)
 bounds=list(map(int,re.findall(r'\d+',matches[0].get('bounds',''))));adb(f'input tap {(bounds[0]+bounds[2])//2} {(bounds[1]+bounds[3])//2}')
def texts(root):return '\n'.join(n.get('text','') for n in root.iter('node'))
def request(method,path,body=b'',timeout=30):
 c=http.client.HTTPConnection(HOST,8786,timeout=timeout);c.request(method,path,body,{'Origin':'http://'+HOST+':8786','Content-Type':'application/json','Authorization':'Bearer 0000'});r=c.getresponse();status=r.status;data=r.read();c.close();return status,json.loads(data)
def offer(files):
 with concurrent.futures.ThreadPoolExecutor() as pool:
  f=pool.submit(request,'POST','/offer',json.dumps({'files':files}).encode(),50)
  for i in range(15):
   root=snapshot();content=texts(root)
   if 'Принять передачу?' in content and all(entry['name'] in content for entry in files):click(root,'Принять');break
   if f.done():break
   time.sleep(.3)
  status,data=f.result();assert status==200,(status,data);return data['id']
def upload(id,index,size,delay=.12,partial=False):
 c=http.client.HTTPConnection(HOST,8786,timeout=90);c.putrequest('PUT',f'/upload/{id}/{index}');c.putheader('Origin','http://'+HOST+':8786');c.putheader('Authorization','Bearer 0000');c.putheader('Content-Length',str(size));c.endheaders()
 digest=hashlib.sha256();done=0
 try:
  while done<size:
   block=bytes([index+41])*min(65536,size-done);c.send(block);digest.update(block);done+=len(block);time.sleep(delay)
   if partial and done>=size//2:c.close();return None
  r=c.getresponse();status=r.status;body=r.read();assert status==200,(status,body);return digest.hexdigest()
 finally:c.close()
def assert_empty():
 # Inspect names only. No user file is removed by this script.
 content=texts(snapshot());assert PREFIX not in content,content
 # Private staging and committed synthetic files must not remain after cleanup.
 return content
# PIN is user-controlled; do not change it during tests.
c=http.client.HTTPConnection(HOST,8786);c.request('GET','/');r=c.getresponse();page=r.read().decode();c.close();assert 'const requirePin=false' in page,'Requires selected TV PIN to adapt test'
print('Test started',flush=True)
name=PREFIX+'cancel.bin';id=offer([{'name':name,'size':8*1048576}])
with concurrent.futures.ThreadPoolExecutor() as pool:
 f=pool.submit(upload,id,0,8*1048576,.15)
 root=snapshot();content=texts(root);assert name in content and 'МБ/с' in content and 'осталось' in content,content
 raw=adb('screencap -p',True);raw=raw[raw.index(b'\x89PNG\r\n\x1a\n'):];pathlib.Path('work/AirTV-progress-on-tv.png').write_bytes(raw)
 click(root,'Отменить')
 try:f.result();raise AssertionError('Cancel did not stop upload')
 except (OSError,AssertionError) as e:
  if str(e)=='Cancel did not stop upload':raise
 time.sleep(1);assert_empty();assert request('POST','/finish/'+id)[0]==400
print('PASS TV live progress and cancellation',flush=True)
name=PREFIX+'disconnect.bin';id=offer([{'name':name,'size':2*1048576}]);upload(id,0,2*1048576,.02,True);time.sleep(1);assert_empty();assert request('POST','/finish/'+id)[0]==400
print('PASS interrupted upload cleaned up',flush=True)
files=[{'name':PREFIX+'first.bin','size':1048576},{'name':PREFIX+'second.bin','size':2*1048576}];id=offer(files);expected={}
expected[files[0]['name']]=upload(id,0,files[0]['size'],.01)
with concurrent.futures.ThreadPoolExecutor() as pool:
 f=pool.submit(upload,id,1,files[1]['size'],.2)
 content=texts(snapshot());assert '2 из 2' in content and '3.0 МБ' in content,content
 expected[files[1]['name']]=f.result()
status,receipt=request('POST','/finish/'+id);assert status==200,(status,receipt);actual={f['name']:f['sha256'] for f in receipt['files']};assert expected==actual
print('PASS multi-file totals and saved-byte SHA256',flush=True)
for file in files:
 root=snapshot();nodes=list(root.iter('node'));name_node=next(n for n in nodes if n.get('text')==file['name']);y=list(map(int,re.findall(r'\d+',name_node.get('bounds',''))))[1]
 delete=[n for n in nodes if n.get('text')=='Удалить' and abs(list(map(int,re.findall(r'\d+',n.get('bounds',''))))[1]-y)<100];assert len(delete)==1
 b=list(map(int,re.findall(r'\d+',delete[0].get('bounds',''))));adb(f'input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}')
 root=snapshot();assert file['name'] in texts(root) and 'Удалить файл?' in texts(root);click(root,'Удалить');time.sleep(.5)
assert_empty();print('PASS synthetic files deleted; receiver ready',flush=True)
