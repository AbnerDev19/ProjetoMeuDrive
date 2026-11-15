"""Package the PC companion while preserving paths used to locate offline UI assets."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--output', default=str(root / 'MeuDrive-PC.zip'))
args = parser.parse_args()
files = [root / p for p in ('scripts/pc-server.py', 'scripts/requirements-pc.txt', 'README.md')]
files += [p for p in (root / 'app/src/main/assets').rglob('*') if p.is_file()]
with ZipFile(args.output, 'w', ZIP_DEFLATED) as archive:
    for file in files:
        archive.write(file, file.relative_to(root).as_posix())
print(args.output)
