"""Isolated WsgiDAV mutation fixture; see tools/local-library/README.md."""
from pathlib import Path
import argparse
import sys
import logging
import json
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
base = args.output.resolve()
artifacts = Path(__file__).resolve().parents[2] / '.test-artifacts'
if not base.is_relative_to(artifacts.resolve()):
    parser.error('--output must be inside this repository\'s .test-artifacts directory')
base.mkdir(parents=True, exist_ok=True)
from wsgidav.wsgidav_app import WsgiDAVApp
from cheroot.wsgi import Server
import wsgidav
root = base / 'dav-root'
root.mkdir(exist_ok=True)
for name in ('strong', 'weak', 'missing', 'bare'):
    (root / name).mkdir(exist_ok=True)
logging.basicConfig(filename=str(base / 'dav-server.log'), level=logging.WARNING)
app = WsgiDAVApp({'provider_mapping': {'/': str(root)}, 'simple_dc': {'user_mapping': {'*': True}}, 'http_authenticator': {'accept_basic': True, 'accept_digest': False}, 'verbose': 0})
# The actual WsgiDAV filesystem and lock manager run unchanged. Only advertised validators
# are quoted, weakened, or removed to exercise conditional mutation paths.
def filtered(environ, start_response):
    mode = environ.get('PATH_INFO', '').split('/')[1]
    if environ['REQUEST_METHOD'] != 'PROPFIND' or mode not in ('strong', 'weak', 'missing'):
        return app(environ, start_response)
    captured = {}
    def capture(status, headers, exc_info=None):
        captured.update(status=status, headers=headers)
    response = app(environ, capture)
    try:
        payload = b''.join(response)
    finally:
        if hasattr(response, 'close'): response.close()
    if captured['status'].startswith('207'):
        document = ET.fromstring(payload)
        for props in document.iter('{DAV:}prop'):
            for etag in list(props.findall('{DAV:}getetag')):
                if mode == 'missing': props.remove(etag)
                elif etag.text:
                    raw = etag.text.removeprefix('W/')
                    quoted = raw if raw.startswith('"') else '"' + raw + '"'
                    etag.text = ('W/' if mode == 'weak' else '') + quoted
        payload = ET.tostring(document, encoding='utf-8', xml_declaration=True)
    headers = [(k, v) for k, v in captured['headers'] if k.lower() != 'content-length']
    headers.append(('Content-Length', str(len(payload))))
    start_response(captured['status'], headers)
    return [payload]
(base / 'server-version.json').write_text(json.dumps({'wsgidav': wsgidav.__version__, 'python': sys.version.split()[0], 'port': 18773, 'validator_filter': ['strong', 'weak', 'missing'], 'native_validator_mode': 'bare'}), encoding='utf-8')
print('READY isolated WsgiDAV 18773', flush=True)
def recorded(environ, start_response):
    def capture(status, headers, exc_info=None):
        with (base / 'request-status.jsonl').open('a', encoding='utf-8') as log:
            log.write(json.dumps({'method': environ['REQUEST_METHOD'], 'path': environ.get('PATH_INFO'), 'status': status.split()[0], 'if_present': 'HTTP_IF' in environ, 'if_match_present': 'HTTP_IF_MATCH' in environ}) + '\n')
        return start_response(status, headers, exc_info)
    return filtered(environ, capture)
Server(('127.0.0.1', 18773), recorded).start()
