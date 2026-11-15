import importlib.util
import io
from pathlib import Path
import unittest
import tempfile
import threading
import urllib.request
import contextlib
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('remote', Path(__file__).with_name('remote-access.py'))
remote = importlib.util.module_from_spec(spec); spec.loader.exec_module(remote)

class RemoteTest(unittest.TestCase):
    def test_private_origins_and_https_ports(self):
        self.assertEqual(remote.origin('192.168.1.10'), 'http://192.168.1.10:8080')
        self.assertEqual(remote.origin('http://127.0.0.1:8080/'), 'http://127.0.0.1:8080')
        self.assertEqual(remote.origin('https://192.168.1.10'), 'https://192.168.1.10:443')
        self.assertEqual(remote.origin('100.90.2.3:8080'), 'http://100.90.2.3:8080')
    def test_rejects_public_hosts_credentials_and_paths(self):
        for value in ('https://example.com', '8.8.8.8:8080','http://user:pw@localhost','http://localhost/api','http://localhost?password=x','http://0.0.0.0:8080','file:///etc/passwd','http://localhost#x'):
            with self.subTest(value=value), self.assertRaises(ValueError): remote.origin(value)
    def test_preflight_authenticates_real_server_and_removes_session(self):
        spec = importlib.util.spec_from_file_location('pc_fixture', Path(__file__).with_name('pc-server.py'))
        pc = importlib.util.module_from_spec(spec); spec.loader.exec_module(pc)
        with tempfile.TemporaryDirectory() as tmp:
            drive = pc.Drive(tmp, 'Remote fixture', 'long-password-for-test')
            server = pc.Server(('127.0.0.1', 0), drive)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                base = f'http://127.0.0.1:{server.server_port}'
                self.assertEqual(remote.verify_server(base, 'long-password-for-test')['name'], 'Remote fixture')
                self.assertEqual(drive.tokens, {})
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                req = urllib.request.Request(base + '/api/login', data=b'password=long-password-for-test', headers={'X-Forwarded-Proto': 'https'})
                with opener.open(req) as response:
                    self.assertIn('; Secure', response.headers['Set-Cookie'])
            finally:
                server.shutdown(); server.server_close()

    def test_does_not_open_tunnel_for_short_password(self):
        with self.assertRaises(ValueError): remote.verify_server('http://localhost:8080', 'short')
    def test_process_args_preserve_origin_without_credentials(self):
        child = Mock()
        child.stdout = io.StringIO('https://test-link.trycloudflare.com\n')
        child.poll.return_value = 0
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch.object(remote.subprocess,'Popen',return_value=child) as popen:
            with self.assertRaises(RuntimeError):
                remote.run_tunnel('cloudflared','http://192.168.1.2:8080')
        args=popen.call_args.args[0]
        self.assertEqual(args[args.index('--url')+1],'http://192.168.1.2:8080')
        self.assertNotIn('--token',args)
        self.assertNotIn('SEU ENDEREÇO EXTERNO', output.getvalue())

if __name__=='__main__': unittest.main()
