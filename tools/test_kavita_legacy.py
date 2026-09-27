"""Run opt-in protocol tests against a fresh, loopback-only official Kavita 0.8 Windows runtime.

Extract the official distribution into .test-artifacts/kavita/server-0.8.0 first.
Only generated fixture content is used. Credentials remain in memory; the process is always stopped.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import json
import os
import secrets
import shutil
import socket
import struct
import subprocess
import threading
import time
import urllib.error
import urllib.request
import zipfile
import zlib


def create_fixtures(root):
    def png():
        def chunk(kind, body):
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body))
        return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 400, 600, 8, 2, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress((b'\0' + b'\x80\xc0\xe0' * 400) * 600)) + chunk(b'IEND', b''))
    for name in ('Comics', 'Books', 'PDF'):
        (root / name / 'Series').mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(root / 'Comics/Series/Legacy Comic v01.cbz', 'w') as archive:
        for i in range(6):
            archive.writestr(f'{i:03}.png', png())
    with zipfile.ZipFile(root / 'Books/Series/Legacy Book.epub', 'w') as archive:
        archive.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)
        archive.writestr('META-INF/container.xml', '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        manifest = ''.join(f'<item id="p{i}" href="p{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(3))
        spine = ''.join(f'<itemref idref="p{i}"/>' for i in range(3))
        archive.writestr('book.opf', f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">koharia-legacy-fixture</dc:identifier><dc:title>Legacy Book</dc:title><dc:language>zh-CN</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest>{manifest}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine>{spine}</spine></package>')
        archive.writestr('nav.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="p0.xhtml">Beginning</a></li></ol></nav></body></html>')
        for i in range(3):
            archive.writestr(f'p{i}.xhtml', f'<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter {i}</title></head><body><p id="anchor-{i}">中文阅读测试。Koharia legacy fixture page {i}.</p></body></html>')
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R] /Count 3 >>']
    for i in range(3):
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 600] /Resources << /Font << /F1 9 0 R >> >> /Contents {4+i*2} 0 R >>'.encode())
        content = f'BT /F1 20 Tf 40 400 Td (Legacy PDF page {i+1}) Tj ET'.encode()
        objects.append(f'<< /Length {len(content)} >>\nstream\n'.encode() + content + b'\nendstream')
    objects.append(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
    pdf = bytearray(b'%PDF-1.4\n')
    offsets = [0]
    for n, body in enumerate(objects, 1):
        offsets.append(len(pdf))
        pdf.extend(f'{n} 0 obj\n'.encode() + body + b'\nendobj\n')
    xref = len(pdf)
    pdf.extend(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
    for offset in offsets[1:]:
        pdf.extend(f'{offset:010} 00000 n \n'.encode())
    pdf.extend(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF'.encode())
    (root / 'PDF/Series/Legacy PDF.pdf').write_bytes(pdf)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', choices=('0.8.0', '0.8.8'), default='0.8.0')
    version = parser.parse_args().version
    repo = Path(__file__).resolve().parents[1]
    artifacts = repo / ('.test-artifacts/kavita/legacy-live-' + version)
    artifacts.mkdir(parents=True, exist_ok=True)
    runtime = repo / ('.test-artifacts/kavita/server-' + version + '/Kavita')
    if not (runtime / 'Kavita.exe').is_file():
        raise RuntimeError('Official runtime is missing')
    if any((runtime / 'config').glob('*.db')):
        raise RuntimeError('Refusing to reuse an existing server database')
    config_path = runtime / 'config/appsettings.json'
    original = config_path.read_bytes() if config_path.exists() else None
    config = json.loads(original.decode('utf-8-sig')) if original else json.loads(
        (runtime / 'config/appsettings-init.json').read_text(encoding='utf-8-sig'))
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    config.update(Port=port, IpAddresses='127.0.0.1')
    config_path.write_text(json.dumps(config), encoding='utf-8')
    content = artifacts / 'content'
    create_fixtures(content)
    base = f'http://127.0.0.1:{port}/'
    token = None
    user = None
    process = None
    bridge = None
    report = {'version': version, 'stage': 'startup'}
    def request(path, body=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        req = urllib.request.Request(base + 'api/' + path, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
        with urllib.request.urlopen(req, timeout=30) as response:
            data = response.read()
            return json.loads(data) if data else None
    result = 1
    try:
        process = subprocess.Popen([str(runtime / 'Kavita.exe')], cwd=runtime,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        for _ in range(90):
            if process.poll() is not None:
                raise RuntimeError('Server exited before startup')
            try:
                with urllib.request.urlopen(base, timeout=1):
                    break
            except (OSError, urllib.error.URLError):
                time.sleep(1)
        else:
            raise RuntimeError('Server startup timed out')
        report['stage'] = 'register'
        user = request('Account/register', {'username': 'fixture', 'email': 'fixture@example.invalid',
                                           'password': 'Aa1!' + secrets.token_urlsafe(18)})
        token = user['token']
        print('Temporary server account registered.', flush=True)
        credential_bytes = json.dumps({'server': base, 'key': user['apiKey']}).encode()
        report['stage'] = 'libraries'
        for name, library_type, file_format in [('Comics', 0, 1), ('Books', 2, 3), ('PDF', 0, 4)]:
            request('Library/create', {'name': name, 'type': library_type, 'folders': [str((content / name).resolve())],
                                      'enableMetadata': True, 'folderWatching': False, 'includeInDashboard': True, 'includeInRecommended': False,
                                      'manageCollections': False, 'manageReadingLists': False, 'allowScrobbling': False,
                                      'fileGroupTypes': [1, 2, 3, 4], 'excludePatterns': []})
            for _ in range(90):
                series = request('Series/all-v2?PageNumber=1&PageSize=50', {'statements': [], 'combination': 1,
                                  'sortOptions': {'sortField': 1, 'isAscending': True}})
                if file_format in {entry['format'] for entry in series}:
                    # 0.8 reschedules a simultaneous library scan for three hours later.
                    time.sleep(2)
                    print(name + ' fixture scanned.', flush=True)
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Fixture scanning did not discover ' + name)
        nonce = '/' + secrets.token_hex(24)
        class Credentials(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != nonce:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header('Content-Length', str(len(credential_bytes)))
                self.end_headers()
                self.wfile.write(credential_bytes)
            def log_message(self, *args):
                pass
        bridge = ThreadingHTTPServer(('127.0.0.1', 0), Credentials)
        threading.Thread(target=bridge.serve_forever, daemon=True).start()
        env = os.environ.copy()
        env['KAVITA_LEGACY_BRIDGE'] = f'http://127.0.0.1:{bridge.server_port}{nonce}'
        env['KAVITA_LEGACY_ARTIFACTS'] = str(artifacts)
        report['stage'] = 'tests'
        print('Isolated official ' + version + ' server scanned comic, Chinese EPUB and PDF fixtures.', flush=True)
        with (artifacts / 'gradle.log').open('w', encoding='utf-8') as log:
            result = subprocess.run([str(repo / 'gradlew.bat'), '--no-daemon', '--no-configuration-cache',
                                     '--console=plain', ':app:testDebugUnitTest', '--rerun', '--tests',
                                     'koharia.kavita.KavitaLegacyLiveTest'], cwd=repo, env=env,
                                    stdout=log, stderr=subprocess.STDOUT).returncode
        report['gradleExitCode'] = result
        junit = repo / 'app/build/test-results/testDebugUnitTest/TEST-koharia.kavita.KavitaLegacyLiveTest.xml'
        if junit.exists():
            shutil.copyfile(junit, artifacts / 'junit.xml')
        report['stage'] = 'complete' if result == 0 else 'tests-failed'
    except urllib.error.HTTPError as error:
        report['httpStatus'] = error.code
    except Exception as error:
        report['errorType'] = type(error).__name__
    finally:
        if bridge:
            bridge.shutdown()
            bridge.server_close()
        if process:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=15)
        if original is None:
            config_path.unlink(missing_ok=True)
        else:
            config_path.write_bytes(original)
        report['serverStopped'] = process is None or process.poll() is not None
        if user:
            for log in (runtime / 'config/logs').glob('*.log'):
                text = log.read_text(encoding='utf-8', errors='replace')
                for field in ('token', 'refreshToken', 'apiKey'):
                    if user.get(field):
                        text = text.replace(user[field], '[redacted]')
                log.write_text(text, encoding='utf-8')
        for name in ('kavita.db', 'kavita.db-shm', 'kavita.db-wal'):
            (runtime / 'config' / name).unlink(missing_ok=True)
        report['temporaryDatabaseRemoved'] = True
        (artifacts / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps(report), flush=True)
    return result


if __name__ == '__main__':
    raise SystemExit(main())
