"""Explicitly scoped synthetic upload checks / lossless old-lab migration on TV .31.
Uploads stream in 64 KiB blocks. No received file bodies are printed or retained locally.
Only the named transfer's observed TV approval dialog is accepted.
"""
import concurrent.futures, hashlib, http.client, json, pathlib, re, shlex, subprocess, sys, time, xml.etree.ElementTree as ET
ADB=pathlib.Path(__file__).resolve().parent/'run-adb-airtv.sh'
HOST='192.168.0.31'; SERIAL=HOST+':5555'; PIN='0000'
def adb(command,binary=False):
    result=subprocess.run([str(ADB),'-s',SERIAL,'exec-out' if binary else 'shell',command],capture_output=True,check=True)
    return result.stdout if binary else result.stdout.decode()
def approve(expected,accept=True,future=None):
    for _ in range(12):
        if future is not None and future.done(): return
        adb('uiautomator dump /sdcard/airtv-integration-ui.xml')
        xml=adb('cat /sdcard/airtv-integration-ui.xml');root=ET.fromstring(xml[xml.index('<?xml'):])
        nodes=list(root.iter('node'));texts='\n'.join(n.get('text','') for n in nodes)
        if 'Принять передачу?' in texts and all(name in texts for name in expected):
            label='Принять' if accept else 'Отклонить'
            candidates=[n for n in nodes if n.get('text','').casefold()==label.casefold() and n.get('package')=='dev.airtv.receiver']
            if len(candidates)!=1:raise RuntimeError('Approval button not uniquely observed')
            numbers=list(map(int,re.findall(r'\d+',candidates[0].get('bounds',''))));x=(numbers[0]+numbers[2])//2;y=(numbers[1]+numbers[3])//2
            adb(f'input tap {x} {y}');return
        time.sleep(.25)
    raise RuntimeError('Expected transfer dialog not observed; no other dialog accepted')
def request(method,path,body=b'',timeout=120):
    connection=http.client.HTTPConnection(HOST,8786,timeout=timeout)
    headers={'Origin':'http://'+HOST+':8786','Authorization':'Bearer '+PIN,'Content-Type':'application/json'}
    connection.request(method,path,body,headers);response=connection.getresponse();data=response.read();status=response.status;connection.close()
    return status,json.loads(data)
def transfer(sources):
    description={'files':[{'name':name,'size':size} for name,size,opener in sources]}
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as worker:
        future=worker.submit(request,'POST','/offer',json.dumps(description).encode(),50)
        approve([source[0] for source in sources],future=future);status,offer=future.result()
    if status!=200:raise RuntimeError(f'Offer failed {status}: {offer}')
    transfer_id=offer['id'];expected={}
    try:
        for i,(name,size,opener) in enumerate(sources):
            digest=hashlib.sha256();connection=http.client.HTTPConnection(HOST,8786,timeout=120)
            connection.putrequest('PUT',f'/upload/{transfer_id}/{i}')
            for key,value in {'Origin':'http://'+HOST+':8786','Authorization':'Bearer '+PIN,'Content-Length':str(size)}.items():connection.putheader(key,value)
            connection.endheaders();remaining=size
            with opener() as stream:
                while remaining:
                    block=stream.read(min(65536,remaining))
                    if not block:raise RuntimeError('Source truncated')
                    digest.update(block);connection.send(block);remaining-=len(block)
                if stream.read(1):raise RuntimeError('Source length differs')
            response=connection.getresponse();payload=response.read();status=response.status;connection.close()
            if status!=200:raise RuntimeError(f'Upload failed {status}: {payload!r}')
            expected[name]=digest.hexdigest()
        status,receipt=request('POST','/finish/'+transfer_id)
        if status!=200:raise RuntimeError(f'Commit failed {status}')
        actual={entry['name']:entry['sha256'] for entry in receipt['files']}
        if actual!=expected:raise RuntimeError('Saved file SHA-256 differs from source')
        print('Verified saved bytes:',len(sources),'file(s),',sum(source[1] for source in sources),'bytes',flush=True)
    except BaseException:
        try:request('POST','/cancel/'+transfer_id)
        except Exception:pass
        raise
class LabStream:
    def __init__(self,path):self.path=path
    def __enter__(self):
        self.process=subprocess.Popen([str(ADB),'-s',SERIAL,'exec-out','run-as dev.airtv.airdrop.lab cat '+shlex.quote(self.path)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        return self.process.stdout
    def __exit__(self,*args):
        self.process.stdout.close();self.process.wait(timeout=10)
        if self.process.returncode:raise RuntimeError('Old lab read failed')
        self.process.stderr.close()
def migrate():
    data=adb('run-as dev.airtv.airdrop.lab find files/received -type f -print0',binary=True)
    groups={}
    for raw in data.split(b'\0'):
        if not raw:continue
        path=raw.decode();relative=pathlib.PurePosixPath(path)
        if any(part.startswith('.') for part in relative.parts):continue
        name=relative.name;size=int(adb('run-as dev.airtv.airdrop.lab stat -c %s '+shlex.quote(path)).strip())
        # Each source directory is a distinct transfer; same file names remain distinct.
        group=groups.setdefault(str(relative.parent),[])
        if any(entry[0]==name for entry in group):raise RuntimeError('Duplicate migration name')
        group.append((name,size,lambda p=path:LabStream(p)))
    print('Migration:',sum(map(len,groups.values())),'files in',len(groups),'batches; original data retained',flush=True)
    for sources in groups.values():transfer(sources)
if __name__=='__main__':
    args=sys.argv[1:]
    if '--pin' in args:i=args.index('--pin');PIN=args[i+1];del args[i:i+2]
    if args==['--migrate-lab']:migrate()
    elif args:
        sources=[]
        for arg in args:
            path=pathlib.Path(arg).resolve();sources.append((path.name,path.stat().st_size,lambda p=path:p.open('rb')))
        transfer(sources)
    else:raise SystemExit('Specify synthetic test paths or --migrate-lab')
