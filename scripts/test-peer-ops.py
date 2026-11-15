import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from peer_ops import PeerRegistry, selected, server_id

spec=importlib.util.spec_from_file_location('pc_fixture',Path(__file__).with_name('pc-server.py'))
pc=importlib.util.module_from_spec(spec);spec.loader.exec_module(pc)

class PeerTest(unittest.TestCase):
    def setUp(self):
        self.a=tempfile.TemporaryDirectory();self.b=tempfile.TemporaryDirectory()
        self.source=pc.Drive(self.a.name,'A06 fixture','same-house-password')
        self.destination=pc.Drive(self.b.name,'PC fixture','same-house-password')
        self.servers=[pc.Server(('127.0.0.1',0),d) for d in (self.source,self.destination)]
        for s in self.servers:threading.Thread(target=s.serve_forever,daemon=True).start()
        self.target=dict(name='PC fixture',host='127.0.0.1',port=self.servers[1].server_port)
        self.source.peers['target']=self.target
    def tearDown(self):
        for s in self.servers:s.shutdown();s.server_close()
        self.a.cleanup();self.b.cleanup()
    def call(self,path,params=None,auth=True):
        data=urllib.parse.urlencode(params).encode() if params is not None else None
        request=urllib.request.Request(f'http://127.0.0.1:{self.servers[0].server_port}'+path,data=data,headers={'X-Key':self.source.key} if auth else {})
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request,timeout=10) as r:return r.status,json.load(r)
        except urllib.error.HTTPError as e:return e.code,json.load(e)
    def wait_job(self,ident):
        end=time.monotonic()+12
        while time.monotonic()<end:
            _,job=self.call('/api/peer-send/status?id='+ident)
            if job['status'] in ('done','failed'):return job
            time.sleep(.05)
        self.fail('job did not complete')
    def test_copy_between_real_servers_without_moving_source(self):
        original=bytes(range(256))*40000
        (self.source.root/'movie.mp4').write_bytes(original)
        code,job=self.call('/api/peer-send',dict(target=server_id(self.target['host'],self.target['port']),paths=json.dumps(['movie.mp4'])))
        self.assertEqual(code,200);done=self.wait_job(job['id']);self.assertEqual(done['status'],'done');self.assertEqual(done['completed'],1)
        self.assertEqual((self.destination.root/'Transferencias/movie.mp4').read_bytes(),original)
        self.assertEqual((self.source.root/'movie.mp4').read_bytes(),original)
    def test_unknown_destination_auth_and_escape_rejected(self):
        (self.source.root/'x').write_text('x')
        self.assertEqual(self.call('/api/peer-send',dict(target='http://evil.example',paths='["x"]'))[0],400)
        self.assertEqual(self.call('/api/peer-send',dict(target='x',paths='["x"]'),auth=False)[0],401)
        self.assertEqual(self.call('/api/delete-batch',dict(paths='["../outside"]'))[1]['results'][0]['ok'],False)
        self.assertEqual(self.call('/api/delete-batch',dict(paths='invalid'))[0],400)
    def test_batch_delete_reports_partial_results_and_keeps_folders(self):
        (self.source.root/'remove.txt').write_text('remove')
        (self.source.root/'keep').mkdir();(self.source.root/'keep/child').write_text('keep')
        code,result=self.call('/api/delete-batch',dict(paths='["remove.txt","keep","missing.txt"]'))
        self.assertEqual(code,200);self.assertFalse(result['ok']);self.assertEqual([r['ok'] for r in result['results']],[True,False,False]);self.assertTrue((self.source.root/'keep/child').exists())
    def test_different_house_password_does_not_report_success(self):
        self.destination.key='wrong';(self.source.root/'x.txt').write_text('secret')
        _,job=self.call('/api/peer-send',dict(target=server_id(self.target['host'],self.target['port']),paths='["x.txt"]'))
        done=self.wait_job(job['id']);self.assertEqual(done['status'],'failed');self.assertEqual(done['completed'],0)
        self.assertFalse((self.destination.root/'Transferencias/x.txt').exists())
    def test_peers_lists_servers_and_active_clients_separately(self):
        self.assertEqual(self.call('/api/heartbeat',dict(deviceId='client-one',name='Meu notebook'))[0],200)
        _,peers=self.call('/api/peers');self.assertEqual({p['kind'] for p in peers['items']},{'server','client'})
        now=[0];registry=PeerRegistry(lambda:now[0]);registry.heartbeat('client-test','Phone');now[0]=91;self.assertEqual(registry.list([]),[])
    def test_selection_is_bounded_and_deduplicated(self):
        self.assertEqual(selected('["x","x"]'),['x'])
        for raw in ('[]','[1]','["x",null]',json.dumps(['x']*201)):
            with self.assertRaises(ValueError):selected(raw)

if __name__=='__main__':unittest.main()
