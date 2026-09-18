"""Read / click a freshly observed control on the authorized Air TV .31 only."""
import json,re,subprocess,sys,xml.etree.ElementTree as ET
ADB=['work/run-adb-airtv.sh','-s','192.168.0.31:5555','shell']
def command(text):return subprocess.run(ADB+[text],capture_output=True,check=True,text=True).stdout
def snapshot():
 command('uiautomator dump /sdcard/airtv-controls-ui.xml')
 raw=command('cat /sdcard/airtv-controls-ui.xml');return ET.fromstring(raw[raw.index('<?xml'):])
def describe(root):
 for n in root.iter('node'):
  if n.get('text') or n.get('content-desc') or n.get('checkable')=='true':print(json.dumps({k:n.get(k) for k in ['text','content-desc','checked','clickable','focused','bounds']},ensure_ascii=False))
def click(root,label):
 parents={child:node for node in root.iter() for child in node}
 matches=[n for n in root.iter('node') if n.get('text','').casefold()==label.casefold() or n.get('content-desc','').casefold()==label.casefold()]
 if len(matches)!=1:raise RuntimeError('Control not uniquely observed: '+label)
 n=matches[0]
 while n.get('clickable')!='true':
  if n not in parents:raise RuntimeError('Observed control has no clickable ancestor')
  n=parents[n]
 if n.get('package')!='dev.airtv.receiver':raise RuntimeError('Only Air TV controls allowed')
 values=list(map(int,re.findall(r'\d+',n.get('bounds',''))));command(f'input tap {(values[0]+values[2])//2} {(values[1]+values[3])//2}')
if __name__=='__main__':
 root=snapshot()
 if len(sys.argv)>1:click(root,sys.argv[1]);root=snapshot()
 describe(root)
