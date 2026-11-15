import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import shutil
import subprocess
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
    def test_photo_preview_is_authenticated_resized_oriented_and_cacheable(self):
        from PIL import Image
        image = Image.new('RGB', (1600, 800), 'blue')
        exif = Image.Exif(); exif[274] = 6
        photo = self.drive.root / 'rotated.jpg'
        image.save(photo, exif=exif)
        self.assertEqual(self.call('/api/preview?path=rotated.jpg', auth=False)[0], 401)
        code, data, headers = self.call('/api/preview?path=rotated.jpg&size=160')
        self.assertEqual(code, 200)
        self.assertEqual(headers['Content-Type'], 'image/jpeg')
        self.assertEqual(headers['Content-Disposition'], 'inline')
        self.assertEqual(headers['Cache-Control'], 'private, max-age=60')
        with Image.open(io.BytesIO(data)) as preview:
            self.assertEqual(preview.size, (80, 160))
        code, empty, _ = self.call('/api/preview?path=rotated.jpg&size=160', headers={'If-None-Match': headers['ETag']})
        self.assertEqual(code, 304); self.assertEqual(empty, b'')
        code, full, _ = self.call('/api/preview?path=rotated.jpg&size=999999&full=1')
        self.assertEqual(code, 200)
        with Image.open(io.BytesIO(full)) as preview:
            self.assertLessEqual(max(preview.size), 1280)
        self.assertTrue((self.drive.stage / 'previews').is_dir())
        self.assertEqual(self.call('/api/preview?path=../outside.jpg')[0], 400)

    def test_preview_rejects_active_and_corrupt_files_and_preserves_alpha(self):
        from PIL import Image
        (self.drive.root / 'active.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')
        (self.drive.root / 'bad.jpg').write_bytes(b'not an image')
        for name in ('active.svg', 'bad.jpg'):
            code, raw, headers = self.call('/api/preview?path=' + name)
            self.assertEqual(code, 400)
            self.assertEqual(headers['Content-Type'], 'application/json; charset=utf-8')
        Image.new('RGBA', (80, 40), (0, 0, 0, 0)).save(self.drive.root / 'alpha.png')
        code, data, _ = self.call('/api/preview?path=alpha.png&size=64')
        self.assertEqual(code, 200)
        with Image.open(io.BytesIO(data)) as preview:
            self.assertEqual(preview.size, (64, 32))
            self.assertEqual(preview.getpixel((0, 0)), (255, 255, 255))
        first = self.call('/api/preview?path=alpha.png')[2]['ETag']
        Image.new('RGBA', (81, 40), (255, 0, 0, 255)).save(self.drive.root / 'alpha.png')
        second = self.call('/api/preview?path=alpha.png')[2]['ETag']
        self.assertNotEqual(first, second)

    def test_recursive_gallery_pagination_and_safe_inline_files(self):
        nested=self.drive.root/'DCIM'/'Camera';nested.mkdir(parents=True)
        (nested/'photo.jpg').write_bytes(b'photo');(nested/'video.mp4').write_bytes(bytes(range(100)))
        (self.drive.root/'paper.pdf').write_bytes(b'%PDF-test')
        (self.drive.root/'active.svg').write_text('<svg/>')
        private=self.drive.root/'Android'/'data';private.mkdir(parents=True);(private/'private.jpg').write_bytes(b'private')
        (self.drive.root/'duplicate').symlink_to(nested,target_is_directory=True)
        self.assertEqual(self.call('/api/media',auth=False)[0],401)
        code,raw,_=self.call('/api/media?limit=2')
        page=json.loads(raw);self.assertEqual(code,200);self.assertEqual(page['total'],3);self.assertEqual(len(page['items']),2);self.assertEqual(page['nextOffset'],2);self.assertFalse(page['partial'])
        second=json.loads(self.call('/api/media?offset=2&limit=2')[1]);self.assertIsNone(second['nextOffset'])
        self.assertEqual(len({item['path'] for item in page['items']+second['items']}),3)
        self.assertEqual(json.loads(self.call('/api/media?kind=photo')[1])['items'][0]['path'],'DCIM/Camera/photo.jpg')
        (self.drive.root/'new.png').write_bytes(b'png')
        self.assertEqual(json.loads(self.call('/api/media?kind=photo')[1])['total'],1)
        self.assertEqual(json.loads(self.call('/api/media?kind=photo&refresh=1')[1])['total'],2)
        self.assertEqual(self.call('/api/media?kind=html')[0],400)
        self.assertEqual(self.call('/api/open?path=paper.pdf',auth=False)[0],401)
        self.assertEqual(self.call('/api/open?path=active.svg')[0],400)
        code,raw,headers=self.call('/api/open?path=DCIM/Camera/video.mp4',headers={'Range':'bytes=10-19'})
        self.assertEqual(code,206);self.assertEqual(raw,bytes(range(10,20)));self.assertEqual(headers['Content-Type'],'video/mp4');self.assertTrue(headers['Content-Disposition'].startswith('inline'))
        code,raw,headers=self.call('/api/open?path=paper.pdf',method='HEAD')
        self.assertEqual(code,200);self.assertEqual(raw,b'');self.assertEqual(headers['Content-Type'],'application/pdf');self.assertEqual(headers['Content-Length'],'9')

    def test_pdf_first_page_and_video_thumbnails(self):
        from PIL import Image
        import pymupdf
        pdf=self.drive.root/'document.pdf'
        with pymupdf.open() as document:
            page=document.new_page(width=400,height=200);page.insert_text((20,40),'MeuDrive test');document.save(pdf)
        code,raw,headers=self.call('/api/preview?path=document.pdf&size=160')
        self.assertEqual(code,200);self.assertEqual(headers['Content-Type'],'image/jpeg')
        with Image.open(io.BytesIO(raw)) as im:self.assertEqual(im.size,(160,80))
        executable=shutil.which('ffmpeg')
        if executable:
            video=self.drive.root/'movie.mp4'
            subprocess.run([executable,'-v','error','-f','lavfi','-i','color=c=blue:s=160x90:d=0.2','-c:v','mpeg4','-y',str(video)],check=True)
            code,raw,headers=self.call('/api/preview?path=movie.mp4&size=160')
            self.assertEqual(code,200)
            with Image.open(io.BytesIO(raw)) as im:self.assertLessEqual(max(im.size),160)
        else:
            (self.drive.root/'movie.mp4').write_bytes(b'video')
            self.assertEqual(self.call('/api/preview?path=movie.mp4')[0],400)

    def test_login_cookie_and_logout(self):
        code,_,h=self.call('/api/login','POST',b'password=password',auth=False)
        self.assertEqual(code,200);cookie=h['Set-Cookie'].split(';')[0]
        self.assertEqual(self.call('/api/whoami',headers={'Cookie':cookie},auth=False)[0],200)
        self.assertEqual(self.call('/api/logout','POST',b'',headers={'Cookie':cookie},auth=False)[0],200)
        self.assertEqual(self.call('/api/whoami',headers={'Cookie':cookie},auth=False)[0],401)

if __name__=='__main__': unittest.main()
