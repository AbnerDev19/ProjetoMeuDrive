"""Authenticated file-only batch operations and bounded copies between discovered MeuDrive servers."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import ipaddress
import json
from pathlib import Path
import threading
import time
import urllib.parse
import uuid

CHUNK = 8 * 1024 * 1024


def server_id(host, port):
    return hashlib.sha256(f'{host}:{port}'.encode()).hexdigest()


def selected(raw):
    values = json.loads(raw)
    if not isinstance(values, list) or not 1 <= len(values) <= 200 or any(not isinstance(p, str) or len(p) > 4096 for p in values):
        raise ValueError('Selecione entre 1 e 200 caminhos válidos')
    return list(dict.fromkeys(values))


class PeerRegistry:
    def __init__(self, now=time.time):
        self.now = now
        self.lock = threading.RLock()
        self.clients = {}

    def heartbeat(self, ident, name):
        if not isinstance(ident, str) or not 8 <= len(ident) <= 100 or not all(c.isalnum() or c in '-_.' for c in ident):
            raise ValueError('Identificação de aparelho inválida')
        with self.lock:
            self._prune()
            if len(self.clients) >= 100 and ident not in self.clients:
                oldest = min(self.clients, key=lambda key: self.clients[key]['lastSeen'])
                self.clients.pop(oldest)
            safe = ''.join(c for c in str(name) if ord(c) >= 32).strip()[:80] or 'Navegador conectado'
            self.clients[ident] = dict(id='client-' + ident, kind='client', name=safe, lastSeen=int(self.now() * 1000))
        return dict(ok=True)

    def _prune(self):
        cutoff = int((self.now() - 90) * 1000)
        self.clients = {key: item for key, item in self.clients.items() if item['lastSeen'] >= cutoff}

    def list(self, peers):
        seen = set()
        result = []
        for peer in peers:
            ident = server_id(peer['host'], peer['port'])
            if ident not in seen:
                result.append(dict(id=ident, kind='server', name=peer['name'], host=peer['host'], port=peer['port']))
                seen.add(ident)
        with self.lock:
            self._prune()
            result += sorted((dict(item) for item in self.clients.values()), key=lambda item: item['name'].lower())
        return result


class RemoteFailure(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


class PeerOperations:
    def __init__(self, resolve, peers, key, sender):
        self.resolve, self.peers, self.key, self.sender = resolve, peers, key, sender
        self.lock = threading.RLock()
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix='meudrive-peer-copy')
        self.slots = threading.BoundedSemaphore(2)
        self.jobs = {}
        self.closed = False
        self.connections = set()

    def delete_batch(self, paths):
        if not 1 <= len(paths) <= 200:
            raise ValueError('Seleção inválida')
        results = []
        for path in dict.fromkeys(paths):
            try:
                file = self.resolve(path)
                if not path.strip() or not file.is_file():
                    raise ValueError('Selecione somente arquivos; pastas não são excluídas em lote')
                file.unlink()
                results.append(dict(path=path, ok=True))
            except (ValueError, OSError) as error:
                results.append(dict(path=path, ok=False, error=str(error)))
        return dict(ok=all(item['ok'] for item in results), results=results)

    def send(self, target_id, paths):
        if not 1 <= len(paths) <= 200:
            raise ValueError('Seleção inválida')
        target = next((dict(p) for p in self.peers() if server_id(p['host'], p['port']) == target_id), None)
        if target is None:
            raise ValueError('Destino não encontrado. Atualize Minha rede e escolha um servidor MeuDrive')
        try:
            address = ipaddress.ip_address(target['host'])
            allowed = any(address in ipaddress.ip_network(network) for network in
                          ('10.0.0.0/8', '127.0.0.0/8', '192.168.0.0/16', '172.16.0.0/12', '100.64.0.0/10', '169.254.0.0/16'))
            if not allowed or not 1 <= int(target['port']) <= 65535:
                raise ValueError()
        except (ValueError, TypeError):
            raise ValueError('Endereço do destino inválido') from None
        paths = list(dict.fromkeys(paths))
        for path in paths:
            if not path.strip() or not self.resolve(path).is_file():
                raise ValueError('Selecione somente arquivos disponíveis na pasta compartilhada')
        if not self.key():
            raise ValueError('Defina a senha da casa')
        with self.lock:
            if self.closed:
                raise ValueError('Servidor encerrado')
            if not self.slots.acquire(blocking=False):
                raise ValueError('Já há envios suficientes na fila. Aguarde o envio atual')
            while len(self.jobs) >= 20:
                old = next((key for key, value in self.jobs.items() if value['status'] in ('done', 'failed')), None)
                if old is None: break
                self.jobs.pop(old)
            ident = str(uuid.uuid4())
            job = dict(id=ident, status='queued', target=target['name'], total=len(paths), completed=0, results=[])
            self.jobs[ident] = job
            try:
                self.pool.submit(self._run, job, target, paths)
            except Exception:
                self.jobs.pop(ident, None); self.slots.release(); raise
            return self._snapshot(job)

    @staticmethod
    def _snapshot(job):
        return json.loads(json.dumps(job))

    def status(self, ident):
        with self.lock:
            if ident not in self.jobs:
                raise ValueError('Envio não encontrado; o servidor pode ter sido reiniciado')
            return self._snapshot(self.jobs[ident])

    def _run(self, job, target, paths):
        failed = False
        with self.lock: job['status'] = 'running'
        try:
            for path in paths:
                try:
                    if self.closed: raise ValueError('Servidor encerrado')
                    name = self._copy(target, path)
                    with self.lock:
                        job['completed'] += 1
                        job['results'].append(dict(path=path, ok=True, name=name))
                except Exception as error:
                    failed = True
                    with self.lock:
                        job['error'] = str(error) or 'Conexão interrompida'
                        job['results'].append(dict(path=path, ok=False, error=job['error']))
                if self.closed: break
        finally:
            with self.lock: job['status'] = 'failed' if failed or self.closed else 'done'
            self.slots.release()

    def _copy(self, peer, path):
        file = self.resolve(path)
        stat = file.stat()
        expected = (stat.st_size, stat.st_mtime_ns)
        canonical = file.resolve()
        secret = self.key()
        state = self._json(peer, secret, '/api/transfer/start', dict(path='Transferencias', name=file.name, size=stat.st_size, **{'from': self.sender()}))
        with file.open('rb') as source:
            attempts = 0
            while not state.get('done'):
                if self.closed: raise ValueError('Servidor encerrado')
                current = file.stat()
                if self.resolve(path).resolve() != canonical or (current.st_size, current.st_mtime_ns) != expected:
                    raise ValueError('Arquivo de origem mudou durante o envio')
                offset = state.get('offset')
                if not isinstance(offset, int) or not 0 <= offset < expected[0]:
                    raise ValueError('Destino retornou progresso inválido')
                length = min(CHUNK, expected[0] - offset)
                conn = self._connection(peer)
                try:
                    conn.putrequest('PUT', f'/api/transfer/chunk?id={urllib.parse.quote(state["id"])}&offset={offset}')
                    conn.putheader('X-Key', secret)
                    conn.putheader('Content-Type', 'application/octet-stream')
                    conn.putheader('Content-Length', str(length)); conn.endheaders()
                    source.seek(offset)
                    remaining = length
                    while remaining:
                        if self.closed: raise ValueError('Servidor encerrado')
                        block = source.read(min(256 * 1024, remaining))
                        if not block: raise ValueError('Arquivo de origem mudou')
                        conn.send(block); remaining -= len(block)
                    state = self._response(conn)
                    attempts = 0
                except (OSError, http.client.HTTPException, RemoteFailure) as error:
                    if isinstance(error, RemoteFailure) and error.code < 500 and error.code != 409: raise
                    attempts += 1
                    if attempts >= 3 or self.closed: raise
                    time.sleep(.3 * attempts)
                    state = self._json(peer, secret, '/api/transfer/status?id=' + urllib.parse.quote(state['id']))
                finally:
                    self._disconnect(conn)
        final = file.stat()
        if (final.st_size, final.st_mtime_ns) != expected:
            raise ValueError('A origem mudou; confirme o arquivo recebido no destino')
        return state['name']

    def _connection(self, peer):
        if self.closed: raise ValueError('Servidor encerrado')
        # HTTPConnection never follows redirects or consults environment proxies.
        conn = http.client.HTTPConnection(peer['host'], peer['port'], timeout=120)
        with self.lock: self.connections.add(conn)
        return conn

    def _disconnect(self, conn):
        with self.lock: self.connections.discard(conn)
        conn.close()

    def _json(self, peer, secret, path, params=None):
        conn = self._connection(peer)
        try:
            body = urllib.parse.urlencode(params).encode() if params is not None else None
            headers = {'X-Key': secret}
            if body is not None: headers['Content-Type'] = 'application/x-www-form-urlencoded'
            conn.request('POST' if body is not None else 'GET', path, body=body, headers=headers)
            return self._response(conn)
        finally:
            self._disconnect(conn)

    @staticmethod
    def _response(conn):
        with conn.getresponse() as response:
            raw = response.read(262145)
            if len(raw) > 262144: raise ValueError('Resposta do destino muito grande')
            try: data = json.loads(raw)
            except (ValueError, UnicodeDecodeError): raise ValueError('Destino retornou resposta inválida') from None
            if not 200 <= response.status < 300:
                raise RemoteFailure(response.status, data.get('error', f'Erro de comunicação {response.status}'))
            return data

    def close(self):
        with self.lock:
            self.closed = True
            connections = list(self.connections)
        for conn in connections: self._disconnect(conn)
        self.pool.shutdown(wait=False, cancel_futures=False)
