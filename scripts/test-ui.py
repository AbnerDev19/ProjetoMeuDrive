import json, pathlib
from playwright.sync_api import sync_playwright
root=pathlib.Path(__file__).resolve().parents[1]
previews=root/'build'/'ui-previews';previews.mkdir(parents=True,exist_ok=True)
html=(root/'app/src/main/assets/index.html').read_text()
items=[dict(name='Trabalho & Projetos',dir=True,size=0,mtime=1794700000000),dict(name='Fotos da viagem',dir=True,size=0,mtime=1794700000000),dict(name='Proposta_comercial.pdf',dir=False,size=4200000,mtime=1794700000000),dict(name='<img onerror=alert(1)>.txt',dir=False,size=10,mtime=1794600000000)]
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path='/usr/bin/chromium',args=['--no-sandbox'])
 page=browser.new_page(viewport={'width':390,'height':844},device_scale_factor=1)
 errors=[]; page.on('pageerror',lambda e:errors.append(str(e)))
 state={'auth':False,'uploads':{},'fail_once':True,'chunks':0}
 def route(r):
  req=r.request; url=req.url; path=url.split('test.local')[-1].split('?')[0]
  if path=='/':r.fulfill(content_type='text/html',body=html);return
  if path=='/fonts/jakarta.ttf':r.fulfill(content_type='font/ttf',body=(root/'app/src/main/assets/fonts/jakarta.ttf').read_bytes());return
  if path=='/api/login': state['auth']=True;r.fulfill(json={'ok':True});return
  if not state['auth']:r.fulfill(status=401,json={'error':'Entre com a senha da casa'});return
  if path=='/api/whoami':r.fulfill(json=dict(name='Galaxy A06',total=128000000000,free=75000000000,root='/storage/emulated/0/MeuDrive'));return
  if path=='/api/list':r.fulfill(json={'items':items});return
  if path=='/api/inbox':r.fulfill(json={'items':[dict(id=1,text='Texto enviado do PC',kind='text',time=1794700000000,path='',**{'from':'PC'})]});return
  if path=='/api/peers':r.fulfill(json={'items':[dict(name='Outro celular',host='192.168.1.20',port=8080)]});return
  if path=='/api/transfer/start':
   from urllib.parse import parse_qs
   params=parse_qs(req.post_data); state['uploads']['test']={'id':'test','offset':0,'size':int(params['size'][0]),'name':params['name'][0],'done':False,'chunkSize':8*1024*1024};r.fulfill(json=state['uploads']['test']);return
  if path=='/api/transfer/status':r.fulfill(json=state['uploads']['test']);return
  if path=='/api/transfer/chunk':
   state['chunks']+=1
   if state['fail_once']:state['fail_once']=False;r.fulfill(status=500,json={'error':'Falha simulada'});return
   t=state['uploads']['test'];t['offset']+=len(req.post_data_buffer);t['done']=t['offset']==t['size'];r.fulfill(json=t);return
  if path=='/api/mkdir':r.fulfill(json={'ok':True});return
  if path=='/api/logout':state['auth']=False;r.fulfill(json={'ok':True});return
  r.fulfill(status=404,json={'error':path})
 page.route('http://test.local/**',route)
 page.goto('http://test.local/');page.locator('#password').fill('senha-teste');page.locator('#login-button').click();page.locator('#used').filter(has_text='49.4 GB').wait_for()
 assert page.locator('body').evaluate('(e)=>e.scrollWidth<=innerWidth')
 page.screenshot(path=str(previews/'mobile.png'),full_page=True)
 page.locator('.mobile-nav [data-page="files"]').click();page.locator('#files .file').first.wait_for();page.locator('#search').fill('comercial');assert page.locator('#files .file').count()==1
 page.locator('#files .file-open').click();assert page.locator('#details').is_visible();page.locator('#detail-close').click()
 page.locator('#list-view').click();page.locator('#new-folder').click();page.locator('#input-value').fill('Nova');page.locator('#input-form button.primary').click();page.locator('#input-dialog').wait_for(state='hidden')
 page.locator('#picker').set_input_files({'name':'large.bin','mimeType':'application/octet-stream','buffer':bytes(range(256))*40000})
 page.locator('#transfer-title').filter(has_text='Envio concluído').wait_for(timeout=30000)
 assert state['uploads']['test']['offset']==10240000 and state['chunks']==3
 page.locator('.mobile-nav [data-page="shared"]').click();page.locator('#inbox').get_by_text('Texto enviado do PC').wait_for()
 page.locator('.mobile-nav [data-page="network"]').click();page.locator('#peers').get_by_text('Outro celular').wait_for()
 page.set_viewport_size({'width':1440,'height':1000});page.locator('.nav [data-page="home"]').click();page.locator('#recent .file').first.wait_for();page.screenshot(path=str(previews/'desktop.png'),full_page=True)
 assert page.locator('body').evaluate('(e)=>e.scrollWidth<=innerWidth')
 page.locator('#logout').click();page.locator('#login').wait_for(state='visible')
 assert not errors,errors
 print('PASS: login, mobile/desktop layout, search, file details, folder creation, retry+chunk upload (10.24 MB), inbox, peers, logout; no JavaScript errors')
 browser.close()
