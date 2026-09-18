"""Read-only/invalid-request checks against an explicitly selected lab TV.
No personal files, valid transfer offers or UI approval actions are performed.
Usage: python test-browser-receiver.py 192.168.0.31 CODE-FROM-TV
"""
import http.client
import ipaddress
import json
import socket
import sys
import unittest

HOST=sys.argv[1]; TOKEN=sys.argv[2].replace('-','').lower()
if not ipaddress.ip_address(HOST).is_private or len(TOKEN)!=4: raise SystemExit('Expected local TV address and its code')
sys.argv=sys.argv[:1]
class ReceiverChecks(unittest.TestCase):
    def request(self,path='/offer',token=TOKEN,origin=None,body=None):
        connection=http.client.HTTPConnection(HOST,8786,timeout=5)
        headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'}
        headers['Origin']=origin or 'http://'+HOST+':8786'
        connection.request('POST',path,json.dumps(body or {'files':[{'name':'Synthetic.txt','size':1}]}).encode(),headers)
        response=connection.getresponse();data=response.read();connection.close();return response.status,data
    def rejected(self,body):
        # Malformed offers must be rejected before displaying an approval dialog.
        self.assertEqual(self.request(body=body)[0],400)
    def test_page_and_headers(self):
        connection=http.client.HTTPConnection(HOST,8786,timeout=5);connection.request('GET','/')
        response=connection.getresponse();text=response.read().decode();self.assertEqual(response.status,200)
        self.assertIn("frame-ancestors 'none'",response.getheader('Content-Security-Policy'));self.assertNotIn('Bearer '+TOKEN,text)
        self.assertNotIn('__PIN_REQUIRED__',text)
        self.assertIn('PIN на экране телевизора',text);connection.close()
    def test_wrong_code(self):
        connection=http.client.HTTPConnection(HOST,8786,timeout=5);connection.request('GET','/');response=connection.getresponse();page=response.read().decode();connection.close()
        if 'const requirePin=false' in page: self.skipTest('PIN disabled: wrong-code check does not apply')
        self.assertEqual(self.request(token='0000' if TOKEN!='0000' else '9999')[0],403)
    def test_external_origin(self): self.assertEqual(self.request(origin='https://example.com')[0],403)
    def test_unsafe_names(self):
        for name in ('../Synthetic.txt','/Synthetic.txt','a\\b','.', '..', 'a\x00b','a\nb','x'*256):
            with self.subTest(name=name): self.rejected({'files':[{'name':name,'size':1}]})
    def test_duplicates(self): self.rejected({'files':[{'name':'a','size':1},{'name':'a','size':1}]})
    def test_size_limits(self):
        for files in ([{'name':'a','size':-1}],[{'name':'a','size':9223372036854775807},{'name':'b','size':1}]): self.rejected({'files':files})
        self.assertEqual(self.request(body={'files':[{'name':'a','size':9223372036854775807}]})[0],409)
    def test_count_limits(self):
        for files in ([],[{'name':str(i),'size':1} for i in range(129)]): self.rejected({'files':files})
    def test_ambiguous_framing(self):
        with socket.create_connection((HOST,8786),timeout=5) as connection:
            connection.sendall(('POST /offer HTTP/1.1\r\nHost: '+HOST+':8786\r\nOrigin: http://'+HOST+':8786\r\nAuthorization: Bearer '+TOKEN+'\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n').encode())
            self.assertTrue(connection.recv(1024).startswith(b'HTTP/1.1 400'))
if __name__=='__main__':unittest.main(verbosity=2)
