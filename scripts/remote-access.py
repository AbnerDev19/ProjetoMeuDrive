#!/usr/bin/env python3
"""Create an opt-in, temporary public HTTPS address for an authenticated LAN MeuDrive.

No cloud account or domain is required for Cloudflare Quick Tunnels. The PC and the
server must remain on. Install cloudflared from Cloudflare before running this script.
"""
import argparse
import getpass
import http.cookiejar
import ipaddress
import json
import queue
import re
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request


def origin(value):
    value = value.strip()
    if '://' not in value:
        value = 'http://' + value
    u = urllib.parse.urlsplit(value)
    if u.scheme not in ('http', 'https') or not u.hostname or u.username or u.password or u.path not in ('', '/') or u.query or u.fragment:
        raise ValueError('Use só o endereço local do servidor, sem senha, caminho ou parâmetros')
    if u.hostname.lower() != 'localhost':
        try:
            address = ipaddress.ip_address(u.hostname)
        except ValueError:
            raise ValueError('Use o IP local exibido pelo A06, ou localhost para o servidor PC') from None
        private = address.is_private or address.is_loopback or address in ipaddress.ip_network('100.64.0.0/10')
        if not private or address.is_unspecified or address.is_multicast or (address.is_reserved and not address.is_loopback):
            raise ValueError('O destino precisa ser um IP privado da sua rede')
    port = u.port if u.port is not None else (443 if u.scheme == 'https' else 8080)
    if not 1 <= port <= 65535:
        raise ValueError('Porta inválida')
    host = '[' + u.hostname + ']' if ':' in u.hostname else u.hostname
    return f'{u.scheme}://{host}:{port}'


def verify_server(base, password):
    if len(password) < 12:
        raise ValueError('Antes de publicar, defina uma senha de pelo menos 12 caracteres no MeuDrive')
    cookies = http.cookiejar.CookieJar()
    # Only validated private IPs reach this opener, never an arbitrary internet host.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies))
    request = urllib.request.Request(base + '/api/login', data=urllib.parse.urlencode({'password': password}).encode())
    with opener.open(request, timeout=10) as response:
        data = json.load(response)
        if data.get('ok') is not True:
            raise ValueError('O servidor não confirmou a senha')
    with opener.open(base + '/api/whoami', timeout=10) as response:
        who = json.load(response)
        if not isinstance(who.get('name'), str) or not who.get('root'):
            raise ValueError('O destino não respondeu como MeuDrive')
    # Remove the short-lived preflight browser session; password is never saved or passed to cloudflared.
    with opener.open(urllib.request.Request(base + '/api/logout', data=b''), timeout=10):
        pass
    return who


def run_tunnel(executable, base):
    command = [executable, 'tunnel', '--no-autoupdate', '--url', base, '--protocol', 'http2']
    child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    lines = queue.Queue(maxsize=256)
    def reader():
        try:
            for line in child.stdout:
                try:
                    lines.put_nowait(line)
                except queue.Full:
                    # Drop old operational logs, never block the tunnel on a slow terminal.
                    try: lines.get_nowait()
                    except queue.Empty: pass
                    try: lines.put_nowait(line)
                    except queue.Full: pass
        finally:
            try: lines.put_nowait(None)
            except queue.Full: pass
    worker = threading.Thread(target=reader, daemon=True)
    worker.start()
    issued_url = None
    registered = False
    ready = False
    started = time.monotonic()
    try:
        while child.poll() is None or not lines.empty():
            if not ready and time.monotonic()-started > 90:
                raise RuntimeError('Não foi possível conectar o túnel em 90 segundos. Confira a internet e o cloudflared.')
            try: line = lines.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                break
            match = re.search(r'https://[a-z0-9-]+\.trycloudflare\.com\b', line, re.I)
            if match:
                issued_url = match.group(0)
            if 'Registered tunnel connection' in line:
                registered = True
            if issued_url and registered and not ready and child.poll() is None:
                ready = True
                print('\nSEU ENDEREÇO EXTERNO: ' + issued_url, flush=True)
                print('Túnel conectado. Abra no navegador fora de casa e entre com a senha do MeuDrive.', flush=True)
                print('No app: Fora de casa → endereço remoto. O link muda ao reiniciar este programa.', flush=True)
                print('Deixe o A06 e este PC ligados. Ctrl+C encerra o acesso público.\n', flush=True)
            elif 'error' in line.lower() or 'failed' in line.lower():
                print(line.rstrip(), flush=True)
        raise RuntimeError('O túnel foi encerrado. Nenhum endereço externo permanece ativo.')
    finally:
        if child.poll() is None:
            child.terminate()
            try: child.wait(timeout=5)
            except subprocess.TimeoutExpired: child.kill(); child.wait()
        if child.stdout: child.stdout.close()


def main():
    parser = argparse.ArgumentParser(description='Endereço HTTPS temporário e gratuito para MeuDrive, via PC ligado em casa')
    parser.add_argument('--server', required=True, help='IP do A06:8080 ou localhost:8080 para o servidor PC')
    parser.add_argument('--cloudflared', help='Caminho de cloudflared, se não estiver no PATH')
    args = parser.parse_args()
    try:
        base = origin(args.server)
        executable = args.cloudflared or shutil.which('cloudflared')
        if not executable:
            raise ValueError('Instale cloudflared: https://developers.cloudflare.com/tunnel/downloads/ (Windows: winget install --id Cloudflare.cloudflared -e)')
        password = getpass.getpass('Senha do MeuDrive (mínimo 12 caracteres; não será salva): ')
        who = verify_server(base, password)
        del password
        print(f'Servidor confirmado: {who["name"]} • {base}')
        print('O link permitirá acessar seus arquivos pela internet com a senha da casa.')
        print('Quick Tunnel é temporário, sem garantia de disponibilidade. Não exige abrir portas no roteador.')
        if input('Digite PUBLICAR para abrir o acesso, ou Enter para cancelar: ').strip() != 'PUBLICAR':
            print('Cancelado. Nenhum endereço público foi criado.'); return
        run_tunnel(executable, base)
    except KeyboardInterrupt:
        print('\nAcesso público encerrado.')
    except (ValueError, RuntimeError, OSError) as error:
        parser.exit(1, f'Não foi possível abrir o acesso externo: {error}\n')


if __name__ == '__main__':
    main()
