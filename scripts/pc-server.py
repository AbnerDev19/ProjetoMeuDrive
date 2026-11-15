#!/usr/bin/env python3
"""Share an explicitly chosen PC directory with the same MeuDrive APK/web client."""
import argparse
import getpass
import hashlib
import hmac
import http.server
import json
import io
import warnings
import mimetypes
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import threading
import time
import urllib.parse
import uuid

CHUNK = 8 * 1024 * 1024
RESERVE = 128 * 1024 * 1024
TTL = 24 * 60 * 60
PREVIEW_CACHE = 24 * 1024 * 1024
PREVIEW_FORMATS = {'JPEG', 'PNG', 'WEBP', 'GIF', 'BMP', 'AVIF', 'HEIF', 'HEIC'}
ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets'


class Drive:
    def __init__(self, root, name, password):
        self.root = Path(root).resolve(strict=True)
        if not self.root.is_dir():
            raise ValueError('Escolha uma pasta existente')
        self.name = name
        self.key = hashlib.sha256(password.encode()).hexdigest()
        self.stage = self.root / '.meudrive-transfers'
        self.stage.mkdir(exist_ok=True)
        (self.root / 'Transferencias').mkdir(exist_ok=True)
        self.preview_slots = threading.BoundedSemaphore(2)
        self.preview_lock = threading.Lock()
        self.lock = threading.RLock()
        self.tokens = {}
        self.attempts = {}
        self.peers = {}
        self.inbox = []
        self.zeroconf = None
        self.advertisement = None
        self.browser = None
        for f in self.stage.iterdir():
            if f.is_file() and time.time() - f.stat().st_mtime > TTL:
                f.unlink()

    def resolve(self, rel):
        p = (self.root / rel).resolve()
        if p != self.root and self.root not in p.parents:
            raise ValueError('Caminho fora da pasta compartilhada')
        if self.stage == p or self.stage in p.parents:
            raise ValueError('Área interna protegida')
        return p

    def meta(self, ident):
        if not re.fullmatch(r'[a-f0-9-]{36}', ident):
            raise ValueError('Transferência inválida')
        p = self.stage / (ident + '.json')
        if not p.exists() or time.time() - p.stat().st_mtime >= TTL:
            raise ValueError('Transferência expirada')
        return json.loads(p.read_text('utf-8'))

    def save(self, meta):
        p = self.stage / (meta['id'] + '.json')
        tmp = p.with_suffix('.tmp')
        tmp.write_text(json.dumps(meta), 'utf-8')
        tmp.replace(p)

    def state(self, meta):
        part = self.stage / (meta['id'] + '.part')
        return dict(id=meta['id'], name=meta['name'], size=meta['size'],
                    offset=meta['size'] if meta['done'] else part.stat().st_size,
                    done=meta['done'], chunkSize=CHUNK)

    def finish(self, meta):
        folder = self.resolve(meta['path'])
        original = Path(meta['name'])
        dest = folder / original.name
        part = self.stage / (meta['id'] + '.part')
        index = 1
        # link is an atomic publish that refuses to overwrite, including concurrent files.
        while True:
            try:
                if os.name == 'nt':
                    os.rename(part, dest)  # Windows rename refuses to replace an existing file.
                else:
                    os.link(part, dest)
                break
            except FileExistsError:
                dest = folder / f'{original.stem} ({index}){original.suffix}'
                index += 1
        if part.exists(): part.unlink()
        meta.update(done=True, name=dest.name)
        self.save(meta)
        self.inbox.insert(0, dict(id=time.time_ns() // 1000000, kind='file', text=dest.name,
                                 path=dest.relative_to(self.root).as_posix(), time=int(time.time()*1000),
                                 **{'from': meta['from']}))
        del self.inbox[200:]

    def start(self, path, name, size, sender):
        if not name.strip() or name in ('.', '..') or len(name) > 180 or any(c in name for c in '/\\<>:"|?*') or any(ord(c) < 32 for c in name):
            raise ValueError('Nome de arquivo inválido para o PC')
        if size < 0 or not self.resolve(path).is_dir():
            raise ValueError('Tamanho ou pasta inválida')
        if shutil.disk_usage(self.root).free - RESERVE < size:
            raise ValueError('Espaço insuficiente; mantenha 128 MiB livres')
        ident = str(uuid.uuid4())
        meta = dict(id=ident, path=path, name=name, size=size, done=False, **{'from': sender[:120]})
        (self.stage / (ident + '.part')).touch()
        self.save(meta)
        if size == 0:
            self.finish(meta)
        return self.state(meta)

    def append(self, ident, offset, length, stream):
        meta = self.meta(ident)
        current = self.state(meta)
        if meta['done']:
            return current
        if length <= 0 or length > CHUNK or offset != current['offset'] or length > meta['size'] - offset:
            raise ValueError('Bloco ou offset inválido; consulte o progresso')
        if shutil.disk_usage(self.root).free - RESERVE < length:
            raise ValueError('Espaço insuficiente')
        with (self.stage / (ident + '.part')).open('r+b') as out:
            out.seek(offset)
            try:
                remaining = length
                while remaining:
                    block = stream.read(min(256*1024, remaining))
                    if not block:
                        raise ValueError('Conexão interrompida')
                    out.write(block)
                    remaining -= len(block)
                out.flush()
                os.fsync(out.fileno())
            except Exception:
                out.truncate(offset)
                raise
        if offset + length == meta['size']:
            self.finish(meta)
        else:
            self.save(meta)
        return self.state(meta)

    def preview(self, file, requested=320, full=False):
        try:
            from PIL import Image, ImageOps, UnidentifiedImageError
        except ImportError as error:
            raise ValueError('Instale as dependências do servidor PC para ver fotos') from error
        if not file.is_file():
            raise ValueError('Foto não encontrada')
        edge = 1280 if full else max(64, min(1280, requested))
        st = file.stat()
        key = hashlib.sha256(f'{file.resolve()}|{st.st_size}|{st.st_mtime_ns}|{edge}|v1'.encode()).hexdigest()
        etag = f'"preview-{key}"'
        cache = self.stage / 'previews'
        cache.mkdir(exist_ok=True)
        target = cache / (key + '.jpg')
        try:
            data = target.read_bytes()
            target.touch()
            return data, etag
        except FileNotFoundError:
            pass
        if not self.preview_slots.acquire(blocking=False):
            raise ValueError('Prévias ocupadas; tente novamente')
        try:
            with warnings.catch_warnings():
                warnings.simplefilter('error', Image.DecompressionBombWarning)
                with Image.open(file) as image:
                    if image.format not in PREVIEW_FORMATS or image.width * image.height > 40_000_000:
                        raise ValueError('Formato ou dimensões sem prévia; baixe o original')
                    # JPEG draft decoding reduces memory before EXIF correction and scaling.
                    image.draft('RGB', (edge * 2, edge * 2))
                    oriented = ImageOps.exif_transpose(image)
                    try:
                        oriented.thumbnail((edge, edge), Image.Resampling.LANCZOS)
                        if oriented.mode in ('RGBA', 'LA') or 'transparency' in oriented.info:
                            rgba = oriented.convert('RGBA')
                            rgb = Image.new('RGB', oriented.size, 'white')
                            rgb.paste(rgba, mask=rgba.getchannel('A'))
                            rgba.close()
                        else:
                            rgb = oriented.convert('RGB')
                        try:
                            output = io.BytesIO()
                            rgb.save(output, format='JPEG', quality=82)
                            data = output.getvalue()
                        finally:
                            rgb.close()
                    finally:
                        if oriented is not image:
                            oriented.close()
            with self.preview_lock:
                temp = cache / (key + '-' + secrets.token_hex(8) + '.tmp')
                try:
                    temp.write_bytes(data)
                    temp.replace(target)
                finally:
                    temp.unlink(missing_ok=True)
                cached = sorted(cache.glob('*.jpg'), key=lambda p: p.stat().st_mtime)
                total = sum(p.stat().st_size for p in cached)
                for item in cached:
                    if total <= PREVIEW_CACHE:
                        break
                    length = item.stat().st_size
                    item.unlink()
                    total -= length
            return data, etag
        except (UnidentifiedImageError, Image.DecompressionBombError, Image.DecompressionBombWarning, OSError) as error:
            raise ValueError('Formato sem prévia; baixe o original para abrir') from error
        finally:
            self.preview_slots.release()

    def discover(self, port):
        try:
            from zeroconf import Zeroconf, ServiceInfo, ServiceBrowser, ServiceListener
        except ImportError:
            print('Descoberta automática opcional: pip install -r scripts/requirements-pc.txt')
            return
        drive = self
        class Listener(ServiceListener):
            def add_service(self, zc, service_type, name):
                info = zc.get_service_info(service_type, name)
                if info and name != drive.advertisement.name:
                    hosts = info.parsed_addresses()
                    if hosts:
                        with drive.lock:
                            drive.peers[name] = dict(name=name.removesuffix('._meudrive._tcp.local.'), host=hosts[0], port=info.port)
            def update_service(self, zc, service_type, name):
                self.add_service(zc, service_type, name)
            def remove_service(self, zc, service_type, name):
                with drive.lock:
                    drive.peers.pop(name, None)
        address = lan_ip()
        self.zeroconf = Zeroconf()
        self.advertisement = ServiceInfo('_meudrive._tcp.local.', f'{self.name}._meudrive._tcp.local.',
                                        addresses=[socket.inet_aton(address)], port=port,
                                        properties={'role': 'pc'}, server=f'meudrive-{uuid.uuid4().hex[:8]}.local.')
        self.zeroconf.register_service(self.advertisement, allow_name_change=True)
        self.browser = ServiceBrowser(self.zeroconf, '_meudrive._tcp.local.', Listener())

    def close(self):
        if self.zeroconf:
            self.zeroconf.unregister_service(self.advertisement)
            self.zeroconf.close()


def lan_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(('192.0.2.1', 9))  # chooses a route; no packet is sent
            return sock.getsockname()[0]
    except OSError:
        return socket.gethostbyname(socket.gethostname())


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, drive):
        self.drive = drive
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(address, Handler)
    def process_request(self, request, address):
        if self.slots.acquire(blocking=False):
            try:
                super().process_request(request, address)
            except Exception:
                self.slots.release()
                raise
        else:
            self.shutdown_request(request)
    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = 'MeuDrivePC/1.2'
    def setup(self):
        super().setup()
        self.connection.settimeout(120)
    def log_message(self, *_):
        pass  # paths, credentials and private filenames stay out of logs
    def reply(self, status, data, mime='application/json; charset=utf-8', extra=None):
        body = json.dumps(data, ensure_ascii=False).encode() if isinstance(data, dict) else data
        self.send_response(status)
        self.headers_common(mime, len(body), extra)
        self.wfile.write(body)
    def headers_common(self, mime, length, extra=None):
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(length))
        self.send_header('Connection', 'close')
        self.send_header('Cache-Control', (extra or {}).get('Cache-Control', 'no-store'))
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'")
        for k, v in (extra or {}).items():
            if k != 'Cache-Control': self.send_header(k, v)
        self.end_headers()
        self.close_connection = True
    def do_GET(self): self.handle_api()
    def do_POST(self): self.handle_api()
    def do_PUT(self): self.handle_api()
    def handle_api(self):
        try:
            self.route()
        except (ValueError, KeyError, FileNotFoundError) as e:
            self.reply(400, {'error': str(e)})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True
        except OSError:
            self.reply(500, {'error': 'Não foi possível acessar a pasta compartilhada'})
    def route(self):
        d = self.server.drive
        url = urllib.parse.urlsplit(self.path)
        route = url.path
        params = {k: v[0] for k, v in urllib.parse.parse_qs(url.query, keep_blank_values=True).items()}
        if self.command == 'GET' and route in ('/', '/fonts/jakarta.ttf'):
            p = ASSETS / ('index.html' if route == '/' else 'fonts/jakarta.ttf')
            return self.reply(200, p.read_bytes(), 'text/html; charset=utf-8' if route == '/' else 'font/ttf')
        origin = self.headers.get('Origin')
        if origin and urllib.parse.urlsplit(origin).netloc != self.headers.get('Host'):
            return self.reply(403, {'error': 'Origem inválida'})
        length = int(self.headers.get('Content-Length', '0'))
        if self.command == 'POST':
            if length < 0 or length > 262144:
                raise ValueError('Use envio em blocos')
            params.update({k: v[0] for k, v in urllib.parse.parse_qs(self.rfile.read(length).decode(), keep_blank_values=True).items()})
        cookie = next((x.strip()[4:] for x in self.headers.get('Cookie', '').split(';') if x.strip().startswith('sid=')), '')
        if route == '/api/login' and self.command == 'POST':
            with d.lock:
                now = time.time()
                attempts = d.attempts.get(self.client_address[0], (0, 0))
                if now - attempts[0] < 60 and attempts[1] >= 8:
                    return self.reply(429, {'error': 'Aguarde um minuto'})
                key = hashlib.sha256(params.get('password', '').encode()).hexdigest()
                if not hmac.compare_digest(key, d.key):
                    if len(d.attempts) > 256: d.attempts.clear()
                    d.attempts[self.client_address[0]] = (attempts[0], attempts[1]+1) if now-attempts[0]<60 else (now, 1)
                    return self.reply(401, {'error': 'Senha incorreta'})
                d.tokens = {k: v for k, v in d.tokens.items() if v > now}
                if len(d.tokens) > 128: d.tokens.clear()
                token = secrets.token_hex(32); d.tokens[token] = now+86400
                return self.reply(200, {'ok': True}, extra={'Set-Cookie': f'sid={token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400' + ('; Secure' if self.headers.get('X-Forwarded-Proto') == 'https' else '')})
        if not (hmac.compare_digest(self.headers.get('X-Key', ''), d.key) or d.tokens.get(cookie, 0) > time.time()):
            return self.reply(401, {'error': 'Entre com a senha da casa'})
        reads = {'/api/list','/api/download','/api/preview','/api/whoami','/api/inbox','/api/peers','/api/transfer/status'}
        expected = 'PUT' if route == '/api/transfer/chunk' else 'GET' if route in reads else 'POST'
        if self.command != expected:
            return self.reply(405, {'error': 'Método inválido'})
        rel = params.get('path', '')
        if route == '/api/download':
            return self.download(d.resolve(rel))
        if route == '/api/preview':
            data, etag = d.preview(d.resolve(rel), int(params.get('size', '320')), params.get('full') == '1')
            headers = {'ETag': etag, 'Content-Disposition': 'inline', 'Cache-Control': 'private, max-age=60', 'Vary': 'Cookie, X-Key'}
            if self.headers.get('If-None-Match') == etag:
                return self.reply(304, b'', 'image/jpeg', headers)
            return self.reply(200, data, 'image/jpeg', headers)
        with d.lock:
            if route == '/api/list':
                folder = d.resolve(rel)
                items = []
                for f in folder.iterdir():
                    try:
                        resolved = d.resolve(f.relative_to(d.root))
                        if not resolved.exists(): continue
                        st = f.stat()
                        items.append(dict(name=f.name, dir=f.is_dir(), size=0 if f.is_dir() else st.st_size, mtime=int(st.st_mtime*1000)))
                    except (ValueError, OSError): continue
                return self.reply(200, dict(path=rel, items=sorted(items, key=lambda x:(not x['dir'],x['name'].lower()))))
            if route == '/api/whoami':
                disk=shutil.disk_usage(d.root)
                return self.reply(200, dict(name=d.name, total=disk.total, free=disk.free, root=str(d.root), shared=True))
            if route == '/api/peers': return self.reply(200, dict(items=list(d.peers.values())))
            if route == '/api/inbox': return self.reply(200, dict(items=d.inbox))
            if route == '/api/transfer/start': return self.reply(200, d.start(rel,params.get('name',''),int(params.get('size','-1')),params.get('from','Navegador')))
            if route == '/api/transfer/status': return self.reply(200, d.state(d.meta(params.get('id',''))))
            if route == '/api/transfer/chunk': return self.reply(200, d.append(params.get('id',''),int(params.get('offset','-1')),length,self.rfile))
            if route == '/api/mkdir':
                name=params.get('name','')
                if not name.strip() or name in ('.','..') or any(c in name for c in '/\\<>:"|?*') or any(ord(c)<32 for c in name): raise ValueError('Nome inválido')
                d.resolve((Path(rel)/name).as_posix()).mkdir()
            elif route == '/api/delete':
                f=d.resolve(rel)
                if f==d.root: raise ValueError('Não pode apagar a raiz')
                if f.is_dir(): shutil.rmtree(f)
                else: f.unlink()
            elif route == '/api/inbox/delete':
                d.inbox[:]=[i for i in d.inbox if i['id']!=int(params.get('id','0'))]
            elif route == '/api/send-text':
                text=params.get('text','').strip()
                if not text or len(text)>100000: raise ValueError('Texto vazio ou grande demais')
                d.inbox.insert(0,dict(id=time.time_ns()//1000000,kind='text',text=text,time=int(time.time()*1000),path='',**{'from':params.get('from','Navegador')[:120]}));del d.inbox[200:]
            elif route == '/api/logout':
                d.tokens.pop(cookie,None)
                return self.reply(200,dict(ok=True),extra={'Set-Cookie':'sid=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0'})
            else: return self.reply(404, {'error':'Não encontrado'})
            self.reply(200,dict(ok=True))

    def download(self, file):
        if not file.is_file(): return self.reply(404, {'error':'Arquivo não encontrado'})
        with file.open('rb') as source:
            st=os.fstat(source.fileno()); size=st.st_size
            etag=f'"{size:x}-{st.st_mtime_ns:x}"'; start=0; end=size-1; partial=False
            header=self.headers.get('Range')
            extra={'Accept-Ranges':'bytes','ETag':etag,'Content-Disposition':"attachment; filename*=UTF-8''"+urllib.parse.quote(file.name)}
            if header and self.headers.get('If-Range',etag)==etag:
                match=re.fullmatch(r'bytes=(\d*)-(\d*)',header)
                if match and size:
                    a,b=match.groups()
                    if a: start=int(a);end=min(int(b),size-1) if b else size-1
                    elif b and int(b)>0: start=max(0,size-int(b))
                    else: start=size
                    partial=True
                if not partial or start>=size or end<start:
                    return self.reply(416,{'error':'Intervalo inválido'},extra={'Content-Range':f'bytes */{size}'})
                extra['Content-Range']=f'bytes {start}-{end}/{size}'
            self.send_response(206 if partial else 200)
            self.headers_common(mimetypes.guess_type(file.name)[0] or 'application/octet-stream',end-start+1,extra)
            source.seek(start); remaining=end-start+1
            while remaining:
                block=source.read(min(256*1024,remaining))
                if not block: break
                self.wfile.write(block);remaining-=len(block)


def main():
    parser=argparse.ArgumentParser(description='Compartilha uma pasta do PC com MeuDrive na rede local')
    parser.add_argument('--root',required=True,help='Pasta que você autoriza compartilhar (leitura, envio e exclusão)')
    parser.add_argument('--name',default=socket.gethostname())
    parser.add_argument('--port',default=8080,type=int)
    args=parser.parse_args()
    password=getpass.getpass('Senha da casa (a mesma dos celulares): ')
    if len(password)<6: parser.error('Use pelo menos 6 caracteres')
    drive=Drive(args.root,args.name,password)
    server=Server(('0.0.0.0',args.port),drive)
    try:
        drive.discover(server.server_port)
        print(f'MeuDrive PC: http://{lan_ip()}:{server.server_port}')
        print(f'Pasta autorizada: {drive.root}\nUse apenas rede privada confiável. Ctrl+C para encerrar.')
        server.serve_forever()
    except KeyboardInterrupt: pass
    finally:
        drive.close();server.server_close()

if __name__=='__main__': main()
