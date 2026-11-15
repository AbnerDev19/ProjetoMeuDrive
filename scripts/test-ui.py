"""Exercise mobile/desktop flows, real image decode and recoverable upload failures."""
import json
import pathlib
import struct
import zlib
from urllib.parse import parse_qs, urlsplit
from playwright.sync_api import sync_playwright

root = pathlib.Path(__file__).resolve().parents[1]
previews = root / 'build' / 'ui-previews'
previews.mkdir(parents=True, exist_ok=True)
html = (root / 'app/src/main/assets/index.html').read_text()

def png_chunk(kind, payload):
    return struct.pack('!I', len(payload)) + kind + payload + struct.pack('!I', zlib.crc32(kind + payload))

# A real 2x2 image: assertions check decoding, not merely the presence of an <img>.
png = b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('!2I5B', 2, 2, 8, 2, 0, 0, 0))
png += png_chunk(b'IDAT', zlib.compress(b'\0\xff\0\0\0\xff\0' * 2)) + png_chunk(b'IEND', b'')
items = [
    dict(name='Trabalho & Projetos', dir=True, size=0, mtime=1794700000000),
    dict(name='Fotos da viagem', dir=True, size=0, mtime=1794700000000),
    dict(name='Proposta_comercial.pdf', dir=False, size=4200000, mtime=1794700000000),
    dict(name='Foto.jpg', dir=False, size=len(png), mtime=1794700000000),
    dict(name='Formato.heic', dir=False, size=99, mtime=1794700000000),
    dict(name='Ativo.svg', dir=False, size=99, mtime=1794700000000),
    dict(name='<img onerror=alert(1)>.txt', dir=False, size=10, mtime=1794600000000),
]

with sync_playwright() as p:
    browser = p.chromium.launch(executable_path='/usr/bin/chromium', args=['--no-sandbox'])
    page = browser.new_page(viewport={'width': 390, 'height': 844}, device_scale_factor=1)
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.on('dialog', lambda d: d.accept())
    state = {'auth': False, 'uploads': {}, 'fail_once': True, 'chunks': 0,
             'allow_retry': False, 'heic_ready': False, 'preview_paths': [], 'starts': []}

    def route(r):
        req = r.request
        parsed = urlsplit(req.url)
        path = parsed.path
        query = parse_qs(parsed.query)
        if path == '/':
            r.fulfill(content_type='text/html', body=html)
            return
        if path == '/fonts/jakarta.ttf':
            r.fulfill(content_type='font/ttf', body=(root / 'app/src/main/assets/fonts/jakarta.ttf').read_bytes())
            return
        if path == '/api/login':
            state['auth'] = True
            r.fulfill(json={'ok': True})
            return
        if not state['auth']:
            r.fulfill(status=401, json={'error': 'Entre com a senha da casa'})
            return
        if path == '/api/preview':
            name = query['path'][0]
            state['preview_paths'].append(name)
            if name.endswith('.heic') and not state['heic_ready']:
                r.fulfill(status=415, json={'error': 'Formato não suportado'})
            else:
                r.fulfill(content_type='image/png', body=png)
            return
        if path == '/api/whoami':
            r.fulfill(json=dict(name='Galaxy A06', total=128000000000, free=75000000000, root='/storage/emulated/0/MeuDrive'))
            return
        if path == '/api/list':
            r.fulfill(json={'items': items if not query.get('path') else []})
            return
        if path == '/api/inbox':
            r.fulfill(json={'items': [dict(id=1, text='Texto enviado do PC', kind='text', time=1794700000000, path='', **{'from': 'PC'})]})
            return
        if path == '/api/peers':
            r.fulfill(json={'items': [dict(name='Outro celular', host='192.168.1.20', port=8080)]})
            return
        if path == '/api/transfer/start':
            params = parse_qs(req.post_data, keep_blank_values=True)
            ident = 'upload-' + str(len(state['uploads']))
            transfer = dict(id=ident, offset=0, size=int(params['size'][0]), name=params['name'][0], done=False, chunkSize=8*1024*1024)
            state['uploads'][ident] = transfer
            state['starts'].append(transfer['name'])
            r.fulfill(json=transfer)
            return
        if path == '/api/transfer/status':
            r.fulfill(json=state['uploads'][query['id'][0]])
            return
        if path == '/api/transfer/chunk':
            state['chunks'] += 1
            transfer = state['uploads'][query['id'][0]]
            if transfer['name'] == 'cancel.bin' or (transfer['name'] == 'retry.bin' and not state['allow_retry']):
                r.fulfill(status=400, json={'error': 'Arquivo temporariamente recusado'})
                return
            if state['fail_once']:
                state['fail_once'] = False
                r.fulfill(status=500, json={'error': 'Falha simulada'})
                return
            assert int(query['offset'][0]) == transfer['offset']
            transfer['offset'] += len(req.post_data_buffer)
            transfer['done'] = transfer['offset'] == transfer['size']
            r.fulfill(json=transfer)
            return
        if path == '/api/mkdir':
            r.fulfill(json={'ok': True})
            return
        if path == '/api/logout':
            state['auth'] = False
            r.fulfill(json={'ok': True})
            return
        r.fulfill(status=404, json={'error': path})

    page.route('http://test.local/**', route)
    page.goto('http://test.local/')
    assert not state['preview_paths']  # No unauthenticated file preview request.
    page.locator('#password').fill('senha-teste')
    page.locator('#login-button').click()
    page.locator('#used').filter(has_text='49.4 GB').wait_for()
    assert page.locator('body').evaluate('(e)=>e.scrollWidth<=innerWidth')
    page.screenshot(path=str(previews / 'mobile.png'), full_page=True)

    page.locator('.mobile-nav [data-page="files"]').click()
    page.locator('#folder-count').filter(has_text='7 itens').wait_for()
    page.locator('#search').fill('Foto.jpg')
    image = page.locator('#files .preview img')
    image.scroll_into_view_if_needed()
    page.wait_for_function("document.querySelector('#files .preview img')?.naturalWidth === 2")
    assert image.get_attribute('loading') == 'lazy'
    page.locator('#files .file-open').click()
    page.wait_for_function("document.querySelector('#detail-icon img')?.naturalWidth === 2")
    assert 'original' in page.locator('#detail-preview-status').inner_text()
    page.locator('#detail-close').click()

    page.locator('#search').fill('Formato.heic')
    page.locator('#files .file-open').click()
    page.locator('#detail-preview-retry').wait_for(state='visible')
    assert page.locator('#details .preview-unavailable').is_visible()
    state['heic_ready'] = True
    page.locator('#detail-preview-retry').click()
    page.wait_for_function("document.querySelector('#detail-icon img')?.naturalWidth === 2")
    assert page.locator('#detail-preview-retry').is_hidden()
    page.locator('#detail-close').click()
    assert not any(name.endswith('.svg') for name in state['preview_paths'])

    page.locator('#search').fill('comercial')
    assert page.locator('#files .file').count() == 1
    page.locator('#files .file-open').click()
    assert page.locator('#details').is_visible()
    page.locator('#detail-close').click()
    page.locator('#list-view').click()
    page.locator('#new-folder').click()
    page.locator('#input-value').fill('Nova')
    page.locator('#input-form button.primary').click()
    page.locator('#input-dialog').wait_for(state='hidden')
    page.locator('#folder-count').filter(has_text='7 itens').wait_for()
    page.locator('#picker').set_input_files({'name': 'large.bin', 'mimeType': 'application/octet-stream', 'buffer': bytes(range(256))*40000})
    page.wait_for_function("document.getElementById('transfer-title').textContent === 'Envio concluído'", timeout=30000)
    assert state['uploads']['upload-0']['offset'] == 10240000 and state['chunks'] == 3
    assert 'Galaxy A06 / MeuDrive' in page.locator('#transfer-destination').inner_text()
    page.locator('#transfer-action').click()

    # A failed second file must not resend a successful first file on retry.
    page.locator('#picker').set_input_files([
        {'name': 'done.bin', 'mimeType': 'application/octet-stream', 'buffer': b'done'},
        {'name': 'retry.bin', 'mimeType': 'application/octet-stream', 'buffer': b'retry'},
    ])
    page.locator('#transfer-action').filter(has_text='Tentar pendentes').wait_for()
    assert '1 de 2 guardado(s)' in page.locator('#transfer-info').inner_text()
    state['allow_retry'] = True
    page.locator('#transfer-action').click()
    page.wait_for_function("document.getElementById('transfer-title').textContent === 'Envio concluído'")
    assert state['starts'].count('done.bin') == 1
    assert state['starts'].count('retry.bin') == 1
    assert sum(t['done'] for t in state['uploads'].values()) == 3
    page.locator('#transfer-action').click()

    # Aborting during a chunk preserves the queue and consults server offset on resume.
    page.route('http://test.local/api/transfer/chunk?*', lambda r: r.abort('failed'))
    page.locator('#picker').set_input_files({'name': 'pause.bin', 'mimeType': 'application/octet-stream', 'buffer': b'resume me'})
    page.locator('#transfer-action').filter(has_text='Pausar').click()
    page.locator('#transfer-action').filter(has_text='Retomar').wait_for()
    assert not page.locator('#transfer-action').is_disabled()
    page.unroute('http://test.local/api/transfer/chunk?*')
    page.locator('#transfer-action').click()
    page.wait_for_function("document.getElementById('transfer-title').textContent === 'Envio concluído'")
    assert state['starts'].count('pause.bin') == 1
    page.locator('#transfer-action').click()

    # Stopping a queue does not claim to remove files already stored on the server.
    page.locator('#picker').set_input_files({'name': 'cancel.bin', 'mimeType': 'application/octet-stream', 'buffer': b'keep resumable state'})
    page.locator('#transfer-action').filter(has_text='Tentar pendentes').wait_for()
    page.locator('#transfer-stop').click()
    assert page.locator('#transfer-title').inner_text() == 'Fila encerrada'
    assert '24 horas' in page.locator('#transfer-info').inner_text()
    assert any(t['name'] == 'cancel.bin' and not t['done'] for t in state['uploads'].values())
    page.locator('#transfer-action').click()

    page.evaluate("window.dispatchEvent(new Event('offline'))")
    assert page.locator('#connection-banner').is_visible()
    page.locator('#connection-retry').click()
    page.locator('#connection-banner').wait_for(state='hidden')
    page.locator('.mobile-nav [data-page="shared"]').click()
    page.locator('#inbox').get_by_text('Texto enviado do PC').wait_for()
    page.locator('.mobile-nav [data-page="network"]').click()
    page.locator('#peers').get_by_text('Outro celular').wait_for()
    assert 'HTTP' in page.locator('#secure-note').inner_text()
    page.locator('.mobile-nav [data-page="files"]').click()
    page.locator('#folder-count').filter(has_text='7 itens').wait_for()
    page.locator('#search').fill('Fotos da viagem')
    page.locator('#files .file-open').click()
    page.locator('#folder-count').filter(has_text='0 itens').wait_for()
    page.locator('#back-folder').click()
    page.locator('#folder-count').filter(has_text='7 itens').wait_for()
    assert page.locator('#back-folder').is_disabled()

    page.set_viewport_size({'width': 1440, 'height': 1000})
    page.locator('.nav [data-page="home"]').click()
    page.locator('#recent .file').first.wait_for()
    page.screenshot(path=str(previews / 'desktop.png'), full_page=True)
    assert page.locator('body').evaluate('(e)=>e.scrollWidth<=innerWidth')
    page.locator('#logout').click()
    page.locator('#login').wait_for(state='visible')
    assert not errors, errors
    print('PASS: mobile/desktop, decoded photos, preview fallback/retry, no active SVG preview, navigation, upload retry+pause, failed-only batch retry, stop queue, destination, offline recovery, inbox, peers, logout')
    browser.close()
