import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

spec=importlib.util.spec_from_file_location('pc_server',Path(__file__).with_name('pc-server.py'))
pc=importlib.util.module_from_spec(spec);spec.loader.exec_module(pc)

class ServerTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.drive=pc.Drive(self.temp.name,'Meu PC','password')
        self.server=pc.Server(('127.0.0.1',0),self.drive)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.temp.cleanup()
    def call(self,path,method='GET',body=None,headers=None,auth=True):
        h={'X-Key':self.drive.key} if auth else {}
        h.update(headers or {})
        req=urllib.request.Request(f'http://127.0.0.1:{self.server.server_port}'+path,data=body,headers=h,method=method)
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(req) as r: return r.status,r.read(),r.headers
        except urllib.error.HTTPError as r: return r.code,r.read(),r.headers
    def test_protocol_and_ranges(self):
        payload=b'abcd'*100000
        code,raw,_=self.call('/api/transfer/start','POST',f'path=Transferencias&name=video.mp4&size={len(payload)}'.encode())
        self.assertEqual(code,200);ident=json.loads(raw)['id']
        for offset in (0,200000):
            code,raw,_=self.call(f'/api/transfer/chunk?id={ident}&offset={offset}','PUT',payload[offset:offset+200000]);self.assertEqual(code,200)
        self.assertTrue(json.loads(raw)['done'])
        self.assertEqual(self.call('/api/download?path=Transferencias/video.mp4')[1],payload)
        code,part,headers=self.call('/api/download?path=Transferencias/video.mp4',headers={'Range':'bytes=10-30'})
        self.assertEqual(code,206);self.assertEqual(part,payload[10:31]);self.assertEqual(headers['Content-Range'],'bytes 10-30/400000')
        self.assertEqual(self.call('/api/download?path=Transferencias/video.mp4',headers={'Range':'bytes=400000-'})[0],416)
        self.assertEqual(len(self.drive.inbox),1)
    def test_auth_paths_origins_and_methods(self):
        self.assertEqual(self.call('/api/list',auth=False)[0],401)
        self.assertEqual(self.call('/api/list?path=../')[0],400)
        self.assertEqual(self.call('/api/list?path=.meudrive-transfers')[0],400)
        self.assertEqual(self.call('/api/delete?path=Transferencias')[0],405)
        self.assertEqual(self.call('/api/mkdir','POST',b'name=evil',headers={'Origin':'http://other.test'})[0],403)
        self.assertFalse((self.drive.root/'evil').exists())
    def test_restart_rollback_collision_zero_and_symlink_escape(self):
        state=self.drive.start('','x.txt',4,'test');ident=state['id']
        with self.assertRaises(ValueError): self.drive.append(ident,0,4,io.BytesIO(b'a'))
        self.assertEqual(self.drive.state(self.drive.meta(ident))['offset'],0)
        restarted=pc.Drive(self.temp.name,'PC','password')
        self.assertTrue(restarted.append(ident,0,4,io.BytesIO(b'test'))['done'])
        self.assertTrue(restarted.start('','x.txt',0,'test')['done'])
        self.assertEqual((self.drive.root/'x.txt').read_bytes(),b'test')
        self.assertTrue((self.drive.root/'x (1).txt').exists())
        with tempfile.TemporaryDirectory() as other:
            link=self.drive.root/'outside';link.symlink_to(other,target_is_directory=True)
            with self.assertRaises(ValueError): self.drive.resolve('outside')
    def test_login_cookie_and_logout(self):
        code,_,h=self.call('/api/login','POST',b'password=password',auth=False)
        self.assertEqual(code,200);cookie=h['Set-Cookie'].split(';')[0]
        self.assertEqual(self.call('/api/whoami',headers={'Cookie':cookie},auth=False)[0],200)
        self.assertEqual(self.call('/api/logout','POST',b'',headers={'Cookie':cookie},auth=False)[0],200)
        self.assertEqual(self.call('/api/whoami',headers={'Cookie':cookie},auth=False)[0],401)

if __name__=='__main__': unittest.main()
