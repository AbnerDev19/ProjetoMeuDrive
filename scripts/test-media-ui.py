"""End-to-end media, batch and layout checks against real servers, including offline PDF.js."""
import importlib.util
import json
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import threading
import time
from PIL import Image
import pymupdf
from playwright.sync_api import sync_playwright, expect
from peer_ops import server_id

root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('pc_media',root/'scripts/pc-server.py');pc=importlib.util.module_from_spec(spec);spec.loader.exec_module(pc)
with tempfile.TemporaryDirectory() as a,tempfile.TemporaryDirectory() as b:
    source=pc.Drive(a,'A06 biblioteca','media-test-password');target=pc.Drive(b,'Notebook destino','media-test-password')
    folder=source.root/'DCIM';folder.mkdir()
    for n in range(62):
        Image.new('RGB',(240,120),(n*3%255,60,180)).save(folder/f'foto-{n:03d}.jpg')
        os.utime(folder/f'foto-{n:03d}.jpg',(1000+n,1000+n))
    pdf=pymupdf.open();page=pdf.new_page();page.insert_text((50,50),'Documento PDF pagina 1');pdf.new_page().insert_text((50,50),'Segunda pagina');pdf.save(source.root/'documento.pdf');pdf.close()
    subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','color=blue:s=256x144:r=12','-t','1','-an','-c:v','libvpx','-pix_fmt','yuv420p',str(source.root/'video.webm')],check=True)
    (source.root/'a-apagar.txt').write_text('selected for deletion')
    (source.root/'b-manter.txt').write_text('keep untouched')
    longname='Arquivo_com_nome_muito_longo_para_conferir_alinhamento_'*2+'.txt';(source.root/longname).write_text('long')
    servers=[pc.Server(('127.0.0.1',0),d) for d in (source,target)]
    for s in servers:threading.Thread(target=s.serve_forever,daemon=True).start()
    peer=dict(name='Notebook destino',host='127.0.0.1',port=servers[1].server_port);source.peers['pc']=peer
    try:
        with sync_playwright() as p:
            browser=p.chromium.launch(executable_path=os.environ.get('MEUDRIVE_CHROMIUM') or shutil.which('chromium') or shutil.which('google-chrome'),args=['--no-sandbox'])
            print('Browser:',browser.version);page=browser.new_page(viewport=dict(width=390,height=844));errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.on('dialog',lambda d:d.accept())
            page.goto(f'http://127.0.0.1:{servers[0].server_port}');page.locator('#password').fill('media-test-password');page.locator('#login-button').click();page.locator('#used').filter(has_text='GB').wait_for()
            nav=lambda name:page.locator(('.mobile-nav' if page.viewport_size['width']<=720 else '.nav')+f' [data-page="{name}"]').click()
            nav('gallery');expect(page.locator('#gallery-count')).to_contain_text('60 de 64');page.locator('#gallery-more').click();expect(page.locator('#gallery-count')).to_contain_text('64 de 64')
            page.locator('#gallery-kind').select_option('photo');expect(page.locator('#gallery-count')).to_contain_text('60 de 62')
            page.locator('#gallery .file-open').first.click();page.wait_for_function('()=>document.querySelector("#detail-icon img")?.naturalWidth>0');page.locator('#detail-close').click()
            page.locator('#gallery-kind').select_option('video');expect(page.locator('#gallery-count')).to_contain_text('1 de 1');page.locator('#gallery .file-open').click();page.wait_for_function('()=>document.querySelector("#detail-icon video")?.readyState>=1');page.locator('#detail-icon video').evaluate('(v)=>v.play()');page.wait_for_function('()=>document.querySelector("#detail-icon video")?.currentTime>0');page.locator('#detail-close').click();expect(page.locator('#detail-icon video')).to_have_count(0)
            page.locator('#gallery-kind').select_option('pdf');expect(page.locator('#gallery-count')).to_contain_text('1 de 1');page.locator('#gallery .file-open').click()
            frame=page.frame_locator('#detail-icon iframe');expect(frame.locator('#count')).to_have_text('1 / 2',timeout=30000);frame.locator('#next').click();expect(frame.locator('#count')).to_have_text('2 / 2');assert frame.locator('#page').evaluate('(c)=>c.width>100&&c.height>100');page.locator('#detail-close').click()
            nav('files');page.locator('#search').fill('a-apagar');card=page.locator('#files .file-open');card.hover();page.mouse.down();page.wait_for_timeout(600);page.mouse.up();expect(page.locator('#selection-count')).to_have_text('1 selecionado(s)');page.locator('#selection-delete').click();page.wait_for_function('()=>document.getElementById("selection-bar").hidden');assert not (source.root/'a-apagar.txt').exists();assert (source.root/'b-manter.txt').exists()
            page.locator('#search').fill('b-manter');page.locator('#files .file-select').check();page.locator('#selection-send').click();page.locator('#peer-picker button').filter(has_text='Notebook destino').click();expect(page.locator('#peer-job')).to_contain_text('Envio concluído',timeout=20000);assert (target.root/'Transferencias/b-manter.txt').read_text()=='keep untouched';assert (source.root/'b-manter.txt').exists();expect(page.locator('#peers')).to_contain_text('Cliente ativo')
            for width in (320,360,390,600,768,1024,1440):
                page.set_viewport_size(dict(width=width,height=900));nav('files');page.locator('#folder-count').filter(has_text='itens').wait_for();page.locator('#search').fill('Arquivo_com_nome');page.locator('#files .file-open').first.wait_for()
                assert page.locator('body').evaluate('(e)=>e.scrollWidth<=innerWidth'),width
                for selector in ['.file','.toolbar','.top','.mobile-nav' if width<=720 else '.nav']:
                    assert page.locator(selector).first.evaluate('(e)=>e.getBoundingClientRect().right<=innerWidth+1'),(width,selector)
            page.set_viewport_size(dict(width=390,height=844));nav('gallery');page.locator('#gallery-kind').select_option('all');page.locator('#gallery .file').first.wait_for();(root/'build/ui-previews').mkdir(parents=True,exist_ok=True);page.screenshot(path=str(root/'build/ui-previews/media-final.png'))
            assert not errors,errors
            browser.close();print('PASS: real photo/video/PDF rendering+pagination, 64-media gallery, longpress delete, peer copy, active clients, responsive 320–1440, no JS errors')
    finally:
        for s in servers:s.shutdown();s.server_close()
