#!/usr/bin/env python3
"""Synthetic loopback tests; never accesses personal files or a real AirDrop peer."""
import datetime, gzip, hashlib, http.client, os, pathlib, plistlib, shutil, socket, ssl, struct, subprocess, tempfile, unittest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID
ROOT = pathlib.Path(__file__).resolve().parent
JAVA = pathlib.Path(os.environ['JAVA_HOME'])/'bin/java' if os.environ.get('JAVA_HOME') else pathlib.Path(shutil.which('java') or '')

def entry(name, data=b'', mode=0o100600, fmt='odc', links=1):
    name = name.encode() + b'\0'
    if fmt == 'odc':
        header = '070707' + ''.join(f'{v:06o}' for v in (0,1,mode,0,0,links,0)) + f'{0:011o}{len(name):06o}{len(data):011o}'
        return header.encode() + name + data
    fields = (1,mode,0,0,links,0,len(data),0,0,0,0,len(name),sum(data) & 0xffffffff if fmt == 'crc' else 0)
    head = ('070702' if fmt == 'crc' else '070701') + ''.join(f'{v:08x}' for v in fields)
    result = head.encode() + name
    result += b'\0' * (-len(result) % 4)
    return result + data + b'\0' * (-len(data) % 4)

def archive(entries, fmt='odc'):
    return gzip.compress(b''.join(entry(*e,fmt=fmt) for e in entries) + entry('TRAILER!!!', mode=0, fmt=fmt))

def client_context(directory, name):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1))
            .not_valid_after(now+datetime.timedelta(hours=1)).sign(key,hashes.SHA256()))
    path = directory / (name+'.pem')
    path.write_bytes(cert.public_bytes(serialization.Encoding.PEM) + key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname=False; context.verify_mode=ssl.CERT_NONE
    context.load_cert_chain(path)
    return context

class Protocol(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='airtv-protocol-')
        cls.folder = pathlib.Path(cls.tmp.name); cls.inbox = cls.folder/'inbox'
        cls.classes=cls.folder/'classes'; cls.classes.mkdir()
        source=ROOT.parent/'airplay-upstream/airdrop-core'
        if not source.exists(): source=ROOT.parent.parent/'airdrop-core'
        sources=list((source/'src/main/java').rglob('*.java'))+list((ROOT/'core/src/test/java').rglob('*.java'))
        subprocess.run([str(JAVA.with_name('javac')),'--release','8','-Xlint:-options','-d',str(cls.classes),
                        *map(str,sources)],check=True)
        cls.context = client_context(cls.folder,'first'); cls.other = client_context(cls.folder,'second')
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'AirTV loopback test')])
        now=datetime.datetime.now(datetime.timezone.utc)
        cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
              .serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1))
              .not_valid_after(now+datetime.timedelta(hours=1)).sign(key,hashes.SHA256()))
        server_keys=cls.folder/'server.p12'
        server_keys.write_bytes(pkcs12.serialize_key_and_certificates(b'lab',key,cert,None,
                                serialization.BestAvailableEncryption(b'lab-only')))
        cls.proc = subprocess.Popen([str(JAVA),'-Xmx64m','-cp',str(cls.classes),
           'dev.airtv.airdrop.LabMain',str(server_keys),str(cls.inbox)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        cls.port = int(cls.proc.stdout.readline().split()[1])
    @classmethod
    def tearDownClass(cls):
        cls.proc.stdin.write('\n'); cls.proc.stdin.flush(); cls.proc.wait(timeout=10)
        cls.proc.stdin.close(); cls.proc.stdout.close(); cls.tmp.cleanup()
    def post(self,path,body,ctx=None,chunked=False,kind='application/octet-stream'):
        connection = http.client.HTTPSConnection('127.0.0.1', self.port, context=ctx or self.context, timeout=10)
        headers={'Content-Type':kind}
        connection.request('POST',path, [body[i:i+317] for i in range(0,len(body),317)] if chunked else body,
                           headers, encode_chunked=chunked)
        response=connection.getresponse(); result=response.status,response.read(); connection.close(); return result
    def ask(self, names=('sample.txt',), directories=(), sender='AirTV synthetic test', ctx=None):
        payload={'SenderComputerName':sender,'BundleID':'com.apple.finder','SenderModelName':'Mac','SenderID':'synthetic',
                 'Files':[{'FileName':n,'FileIsDirectory':n in directories,'FileBomPath':'./'+n,'FileType':'public.data'} for n in names]}
        return self.post('/Ask',plistlib.dumps(payload,fmt=plistlib.FMT_BINARY),ctx)[0]
    def upload(self, body, ctx=None, chunked=False):
        return self.post('/Upload',body,ctx,chunked,'application/x-cpio')[0]
    def test_discover_python_interop(self):
        status, data=self.post('/Discover',plistlib.dumps({'SenderRecordData':b'\0synthetic'},fmt=plistlib.FMT_BINARY))
        self.assertEqual(status,200); self.assertEqual(plistlib.loads(data)['ReceiverComputerName'],'Air TV Lab')
    def test_plist_unicode_interop(self):
        payload={'data':b'123','array':[True,False,1,65536,'Привет',{'nested':None}],'real':1.5}
        result=subprocess.run([str(JAVA),'-cp',str(self.classes),'dev.airtv.airdrop.PlistMain'],
               input=plistlib.dumps(payload,fmt=plistlib.FMT_BINARY),capture_output=True,check=True)
        self.assertEqual(plistlib.loads(result.stdout)['ReceiverComputerName'],'Тест Air TV')
    def test_shared_plist_objects_bound_memory(self):
        shared=b'X'*(512*1024)
        payload=plistlib.dumps({'SharedData':[shared]*2048},fmt=plistlib.FMT_BINARY)
        self.assertLess(len(payload),1024*1024)
        self.assertEqual(self.post('/Discover',payload)[0],200)
    def test_approval_required(self):
        self.assertEqual(self.ask(sender='decline synthetic test'),403)
        self.assertEqual(self.upload(archive([('sample.txt',b'data')])),403)
    def test_success_archive_formats(self):
        for fmt in ('odc','newc','crc'):
            with self.subTest(fmt=fmt):
                data=('Привет Air TV '+fmt).encode()
                self.assertEqual(self.ask(('видео.txt',)),200)
                self.assertEqual(self.upload(archive([('./видео.txt',data)],fmt),chunked=True),200)
                self.assertTrue(any(p.read_bytes()==data for p in self.inbox.glob('*/видео.txt')))
                self.assertEqual(self.upload(archive([('видео.txt',data)])),403)
    def test_certificate_binding(self):
        self.assertEqual(self.ask(),200)
        body=archive([('sample.txt',b'certificate bound')])
        self.assertEqual(self.upload(body,ctx=self.other),403)
        self.assertEqual(self.upload(body),200)
    def test_directory(self):
        self.assertEqual(self.ask(('folder',),('folder',)),200)
        self.assertEqual(self.upload(archive([('folder',b'',0o40700),('folder/sub/test.txt',b'child')])),200)
        self.assertTrue(any(p.read_bytes()==b'child' for p in self.inbox.glob('*/folder/sub/test.txt')))
    def test_malformed_archives_cleanup(self):
        cases=[archive([('../sample.txt',b'escape')]),archive([('/sample.txt',b'escape')]),
               archive([('sample.txt',b'link',0o120777)]),archive([('other.txt',b'unapproved')]),
               archive([('sample.txt',b'a'),('sample.txt',b'b')]),archive([('sample.txt',b'badcrc')])[:-4],
               gzip.compress(entry('sample.txt',b'hardlink',links=2)+entry('TRAILER!!!',mode=0)), b'broken']
        for body in cases:
            with self.subTest(size=len(body)):
                self.assertEqual(self.ask(),200)
                self.assertEqual(self.upload(body),400)
                self.assertFalse(list(self.inbox.glob('*.partial')))
    def test_malformed_plist_listener_survives(self):
        cycle=b'bplist00\xa1\x00\x08'+struct.pack('>6xBBQQQ',1,1,1,0,10)
        for body in (b'bad',b'bplist00'+b'\xff'*32,cycle,plistlib.dumps(['array'],fmt=plistlib.FMT_BINARY)):
            self.assertEqual(self.post('/Discover',body)[0],400)
        self.test_discover_python_interop()
    def raw_headers(self, headers):
        with self.context.wrap_socket(socket.create_connection(('127.0.0.1',self.port),timeout=10),server_hostname='localhost') as wire:
            wire.sendall(b'POST /Discover HTTP/1.1\r\nHost: localhost\r\n'+headers+b'\r\n')
            response=http.client.HTTPResponse(wire); response.begin(); status=response.status; response.read(); return status
    def test_http_framing(self):
        for headers in (b'Content-Length: 1\r\nContent-Length: 1\r\n',
                        b'Content-Length: 1\r\nTransfer-Encoding: chunked\r\n',
                        b'Content-Length: 268435457\r\n',b'Content-Length: 0\r\nExpect: 100-continue\r\n'):
            self.assertEqual(self.raw_headers(headers),400)
    def test_expect_continue(self):
        self.assertEqual(self.ask(),200)
        body=archive([('sample.txt',b'continue synthetic')])
        with self.context.wrap_socket(socket.create_connection(('127.0.0.1',self.port),timeout=10),server_hostname='localhost') as wire:
            wire.sendall(('POST /Upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-cpio\r\nExpect: 100-continue\r\nContent-Length: '+str(len(body))+'\r\n\r\n').encode())
            stream=wire.makefile('rb'); self.assertEqual(stream.readline(),b'HTTP/1.1 100 Continue\r\n')
            self.assertEqual(stream.readline(),b'\r\n'); wire.sendall(body)
            self.assertEqual(stream.readline(),b'HTTP/1.1 200 OK\r\n'); stream.close()
    def test_cpio_crc(self):
        body=gzip.decompress(archive([('sample.txt',b'checksum')],'crc')).replace(b'checksum',b'checksun')
        self.assertEqual(self.ask(),200); self.assertEqual(self.upload(gzip.compress(body)),400)
    def test_manifest_paths(self):
        for name in ('../bad','/bad','a/b','a\\b',''):
            self.assertEqual(self.ask((name,)),400)
    def test_large_streaming_transfer_64mb_heap(self):
        data=b'AirTV synthetic payload\0' * (32*1024*1024//24)
        self.assertEqual(self.ask(('large.bin',)),200)
        self.assertEqual(self.upload(archive([('large.bin',data)]),chunked=True),200)
        expected=hashlib.sha256(data).digest()
        self.assertTrue(any(hashlib.sha256(p.read_bytes()).digest()==expected for p in self.inbox.glob('*/large.bin')))

if __name__=='__main__': unittest.main(verbosity=2)
