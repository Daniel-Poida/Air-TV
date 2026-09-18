import concurrent.futures,http.client,json,pathlib,subprocess,threading,time
root=pathlib.Path(__file__).resolve().parent
scope={'__file__':str(root/'test-transfer-progress.py')}
exec((root/'test-transfer-progress.py').read_text().partition('# PIN is user-controlled')[0],scope)
ready=threading.Event();lines=[]
p=subprocess.Popen([str(root/'run-adb-airtv.sh'),'-s','192.168.0.31:5555','shell','am instrument -w -e mode transfer dev.airtv.tests/dev.airtv.tests.RecoveryTest'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
def monitor():
 while True:
  char=p.stdout.read(1)
  if not char:break
  lines.append(char)
  if 'READY' in ''.join(lines):ready.set()
threading.Thread(target=monitor,daemon=True).start()
if not ready.wait(30):raise AssertionError('Instrumentation not ready: '+''.join(lines))
for i in range(40):
 try:status,data=scope['request']('POST','/finish/no-such-transfer');break
 except OSError:time.sleep(.25)
name='AirTV-progress-test-recovery.bin';size=6*1048576
id=scope['offer']([{'name':name,'size':size}]);digest=scope['upload'](id,0,size,.15)
status,receipt=scope['request']('POST','/finish/'+id);assert status==200,(status,receipt)
assert receipt['files'][0]['sha256']==digest
p.wait(timeout=30);assert 'PASS' in ''.join(lines),''.join(lines)
print('PASS real streaming upload survived AirPlay receiver reset, saved SHA256 matches',flush=True)
# Remove only this fixture through the app's observed confirmation.
scope['adb']('am start -n dev.airtv.receiver/io.github.jqssun.airplay.MainActivity')
ui=scope['snapshot']();scope['click'](ui,'AirDrop');ui=scope['snapshot']();assert name in scope['texts'](ui)
scope['click'](ui,'Удалить');ui=scope['snapshot']();assert 'Удалить файл?' in scope['texts'](ui) and name in scope['texts'](ui);scope['click'](ui,'Удалить');time.sleep(.5);scope['assert_empty']()
print('PASS concurrent-test fixture deleted',flush=True)
