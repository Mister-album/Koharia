"""Local LANraragi protocol fixture for Android tests; contains only generated test books."""
import argparse
import base64
import json
import struct
import threading
import time
import urllib.parse
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
STATE = {}
ARCHIVES = [f"{index:040x}" for index in range(1, 6)]
TANKS = {"TANK_1234567890": ARCHIVES[:3], "TANK_1234567891": ["TANK_1234567890", ARCHIVES[3]]}
AUTH = "Bearer " + base64.b64encode(b"fixture-key").decode()


def png(color, height=96):
    def chunk(kind, value):
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value))
    pixels = (b"\0" + bytes(color) * 64) * height
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")


IMAGES = [png((230, 70, 60)), png((40, 170, 90)), png((50, 90, 220), height=1600)]


def state(version):
    return STATE.setdefault(version, {"offline": False, "progress": {}, "new": {arc: True for arc in ARCHIVES}})


def metadata(version, arc):
    progress, read = state(version)["progress"].get(arc, (0, 0))
    index = ARCHIVES.index(arc) + 1
    return {"arcid": arc, "title": f"Fixture book {index}", "tags": f"artist:Fixture, date_added:{index}",
            "summary": "Generated LANraragi integration fixture", "pagecount": 3,
            "progress": progress, "lastreadtime": read, "isnew": state(version)["new"][arc]}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send(self, data, code=200, media="application/json"):
        content = data if isinstance(data, bytes) else json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", media)
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        self.handle_api()

    def do_PUT(self):
        self.handle_api()

    def do_DELETE(self):
        self.handle_api()

    def handle_api(self):
        url = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(url.query)
        if url.path == "/_fixture/control":
            version = query.get("version", ["v80"])[0]
            with LOCK:
                if "reset" in query:
                    STATE.pop(version, None)
                state(version)["offline"] = query.get("offline", ["false"])[0] == "true"
                if "metadata_delay" in query:
                    state(version)["metadata_delay"] = float(query["metadata_delay"][0])
                if "fail_images" in query:
                    state(version)["fail_images"] = query["fail_images"][0] == "true"
            return self.send({"success": 1})
        parts = url.path.strip("/").split("/")
        if len(parts) < 4 or parts[0].split("_test_")[0] not in ("v70", "v80") or parts[1:3] != ["lrr", "api"]:
            return self.send({"success": 0}, 404)
        version = parts[0]
        if self.headers.get("Authorization") != AUTH:
            return self.send({"success": 0}, 401)
        if parts[-1] == "metadata":
            with LOCK:
                pause = state(version).get("metadata_delay", 0)
            time.sleep(pause)
        with LOCK:
            if state(version)["offline"]:
                return self.send({"success": 0}, 503)
            route = parts[3:]
            if route == ["info"]:
                return self.send({"version": "0.9.70" if version.startswith("v70") else "0.9.80", "archives_per_page": 2,
                                  "server_tracks_progress": True, "authenticated_progress": True, "nofun_mode": True})
            if route == ["archives"]:
                return self.send([metadata(version, arc) for arc in ARCHIVES])
            if route == ["archives", "untagged"]:
                return self.send([])
            if route == ["categories"]:
                return self.send([
                    {"id": "SET_1234567890", "name": "Static fixture", "archives": ARCHIVES[:2], "search": "", "pinned": 1},
                    {"id": "SET_1234567891", "name": "Dynamic fixture", "archives": [], "search": "artist:Fixture", "pinned": 0}])
            if route == ["search"]:
                start = int(query.get("start", ["0"])[0])
                arcs = ARCHIVES[::2] if query.get("category") == ["SET_1234567891"] else ARCHIVES
                term = query.get("filter", [""])[0]
                if term == "server-only":
                    arcs = ARCHIVES[-1:]
                elif term:
                    arcs = [arc for arc in arcs if term.lower() in
                            (metadata(version, arc)["title"] + " " + metadata(version, arc)["tags"]).lower()]
                return self.send({"data": [metadata(version, arc) for arc in arcs[start:start + 2]],
                                  "recordsFiltered": len(arcs), "recordsTotal": len(ARCHIVES)})
            if route == ["tankoubons"]:
                page = int(query.get("page", ["0"])[0])
                ids = list(TANKS)[page:page + 1]
                return self.send({"result": [{"id": key, "name": "Fixture collection", "archives": []} for key in ids],
                                  "total": len(TANKS), "filtered": len(ids)})
            if len(route) >= 2 and route[0] == "tankoubons" and route[1] in TANKS:
                if route[-1] == "thumbnail":
                    return self.send(IMAGES[0], media="image/png")
                if (version.startswith("v80") and route[-1] != "full") or (version.startswith("v70") and route[-1] == "full"):
                    return self.send({"success": 0}, 404)
                page = int(query.get("page", ["0"])[0])
                members = TANKS[route[1]]
                return self.send({"result": {"id": route[1], "name": "Fixture collection " + route[1][-1],
                                             "archives": members[page * 2:page * 2 + 2]}, "total": len(members), "filtered": 2})
            if len(route) >= 3 and route[0] == "archives" and route[1] in ARCHIVES:
                arc = route[1]
                if route[2] == "metadata":
                    return self.send(metadata(version, arc))
                if route[2] == "files":
                    return self.send({"job": 0, "pages": [f"./api/archives/{arc}/page?path=chapter%201%2F{index:02}.png" for index in range(1, 4)]})
                if route[2] == "thumbnail":
                    return self.send(IMAGES[0], media="image/png")
                if route[2] == "page":
                    if state(version).get("fail_images", False):
                        return self.send({"success": 0}, 503)
                    index = int(query["path"][0].split("/")[-1].split(".")[0]) - 1
                    return self.send(IMAGES[index], media="image/png")
                if route[2] == "progress" and self.command == "PUT":
                    state(version)["progress"][arc] = (int(route[3]), int(time.time()))
                    return self.send({"success": 1})
                if route[2] == "isnew" and self.command == "DELETE":
                    state(version)["new"][arc] = False
                    return self.send({"success": 1})
            self.send({"success": 0}, 404)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=38709)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"LANraragi fixture listening on 127.0.0.1:{server.server_port}", flush=True)
    server.serve_forever()
