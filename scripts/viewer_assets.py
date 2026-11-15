"""Serve only packaged static PDF.js resources, never arbitrary disk paths."""
from zipfile import ZipFile


def viewer_asset(assets, path):
    if path == '/pdf-viewer.html':
        return (assets / 'pdf-viewer.html').read_bytes(), 'text/html; charset=utf-8'
    if not path.startswith('/pdfjs/'):
        return None
    name = path.removeprefix('/pdfjs/')
    if not name or any(p in ('.', '..') for p in name.split('/')) or '\\' in name:
        return None
    with ZipFile(assets / 'pdfjs.zip') as archive:
        try:
            data = archive.read(name)
        except KeyError:
            return None
    ext = name.rsplit('.', 1)[-1]
    mime = {'mjs': 'text/javascript; charset=utf-8', 'wasm': 'application/wasm', 'ttf': 'font/ttf',
            'otf': 'font/otf', 'txt': 'text/plain; charset=utf-8'}.get(ext, 'application/octet-stream')
    return data, mime
