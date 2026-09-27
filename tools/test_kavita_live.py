"""Opt-in writable Kavita integration tests. Credentials stay in process memory."""

import argparse
import getpass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--device", help="Explicit ADB serial; also runs isolated Android publication tests")
    parser.add_argument("--device-only", action="store_true")
    parser.add_argument("--test", default="koharia.kavita.KavitaLiveTest", help="Opt-in JVM test filter")
    args = parser.parse_args()
    if args.device_only and not args.device:
        parser.error("--device-only requires an explicit --device serial")
    repo = Path(__file__).resolve().parents[1]
    artifacts = repo / ".test-artifacts" / "kavita" / "live"
    artifacts.mkdir(parents=True, exist_ok=True)
    base = args.server.rstrip("/") + "/api/"
    token = None
    key_id = None
    report = {"jvmSkipped": args.device_only, "device": args.device}
    bridge = None
    reverse_port = None

    def request(path, body=None, method=None):
        data = None if body is None else json.dumps(body).encode()
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        # Never forward credentials across redirects.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        with urllib.request.build_opener(NoRedirect).open(req, timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    result = 1
    try:
        if args.device:
            report["stage"] = "device-preflight"
            ready = subprocess.run(["adb", "-s", args.device, "get-state"],
                                   capture_output=True, text=True)
            if ready.returncode != 0 or ready.stdout.strip() != "device":
                print("Selected device is unavailable; start or connect it before running device tests.")
                raise RuntimeError("Selected device unavailable")
        report["stage"] = "authentication"
        password = os.environ.pop("KAVITA_TEST_PASSWORD", None) or getpass.getpass("Test account password: ")
        account = request("Account/login", {"username": args.username, "password": password})
        password = None
        token = account["token"]
        report.update(version=account.get("kavitaVersion"), roles=account.get("roles"))
        key = request("Account/create-auth-key", {
            "name": "Koharia test " + uuid.uuid4().hex[:12],
            "keyLength": 32,
            "expiresUtc": (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat(),
        })
        key_id = key["id"]
        print("Authenticated; temporary Auth Key created. Running opt-in live tests.", flush=True)
        # The Gradle daemon and its configuration cache never receive the actual key.
        nonce = "/" + uuid.uuid4().hex
        credential_bytes = json.dumps({"server": args.server, "key": key["key"]}).encode()
        class Credentials(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != nonce:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(credential_bytes)))
                self.end_headers()
                self.wfile.write(credential_bytes)

            def log_message(self, *args):
                pass

        bridge = ThreadingHTTPServer(("127.0.0.1", 0), Credentials)
        threading.Thread(target=bridge.serve_forever, daemon=True).start()
        port = bridge.server_port
        bridge_url = f"http://127.0.0.1:{port}{nonce}"
        env = os.environ.copy()
        env.update(KAVITA_LIVE_BRIDGE=bridge_url, KAVITA_LIVE_ARTIFACTS=str(artifacts), KAVITA_LIVE_WRITE="true")
        gradle = [str(repo / "gradlew.bat"), "--no-daemon", "--no-configuration-cache", "--console=plain"]

        def run(command, filename):
            with (artifacts / filename).open("w", encoding="utf-8") as log:
                return subprocess.run(command, cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT).returncode

        report["stage"] = "jvm-tests"
        result = 0 if args.device_only else run(gradle + [
            ":app:testDebugUnitTest", "--rerun", "--tests", args.test,
        ], "gradle.log")
        report["gradleExitCode"] = result
        junit = repo / "app/build/test-results/testDebugUnitTest/TEST-koharia.kavita.KavitaLiveTest.xml"
        if not args.device_only and junit.exists():
            shutil.copyfile(junit, artifacts / "live-junit.xml")
        print("Live test Gradle exit code:", result, flush=True)
        if args.device and result == 0:
            report["stage"] = "device-build"
            result = run(gradle + ["-PdeviceTestFixture=true", ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
                         "device-build.log")
            if result != 0:
                raise RuntimeError("Device fixture build failed")
            adb = ["adb", "-s", args.device]
            report["stage"] = "device-install"
            for kind, directory in [("app", repo / "app/build/outputs/apk/debug"),
                                    ("test", repo / "app/build/outputs/apk/androidTest/debug")]:
                metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
                expected = "app.koharia.dev.devicefixture" + (".test" if kind == "test" else "")
                if metadata["applicationId"] != expected:
                    raise RuntimeError("Refusing installation of a non-fixture package")
                candidates = [e for e in metadata["elements"] if not e.get("filters")]
                if len(candidates) != 1:
                    raise RuntimeError("A universal fixture APK is required")
                apk = directory / candidates[0]["outputFile"]
                if run(adb + ["install", "-r", "-t", str(apk)], "device-install-" + kind + ".log") != 0:
                    raise RuntimeError("Fixture install failed; installed packages retained")
            if run(adb + ["reverse", f"tcp:{port}", f"tcp:{port}"], "device-reverse.log") != 0:
                raise RuntimeError("Unable to connect temporary credential bridge")
            reverse_port = port
            report["stage"] = "device-tests"
            result = run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                               "koharia.kavita.KavitaLivePublicationDeviceTest", "-e", "runKavitaLive", "true",
                               "-e", "kavitaLiveBridge", bridge_url,
                               "app.koharia.dev.devicefixture.test/koharia.testing.KohariaDeviceTestRunner"],
                         "device-tests.log")
            output = (artifacts / "device-tests.log").read_text(encoding="utf-8")
            if "OK (" not in output or "FAILURES" in output or "INSTRUMENTATION_FAILED" in output:
                result = 1
            report["device"] = args.device
            report["deviceExitCode"] = result
            print("Device test exit code:", result, flush=True)
        if result == 0:
            report["stage"] = "complete"
    except urllib.error.HTTPError as error:
        print("Live test setup/cleanup HTTP status:", error.code)
        report["httpStatus"] = error.code
        result = 1
    except Exception as error:
        # Exception messages may contain a request URL; do not print them.
        print("Live test setup failed:", type(error).__name__)
        report["errorType"] = type(error).__name__
        result = 1
    finally:
        if reverse_port is not None:
            subprocess.run(["adb", "-s", args.device, "reverse", "--remove", f"tcp:{reverse_port}"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if bridge is not None:
            bridge.shutdown()
            bridge.server_close()
        if key_id is not None:
            try:
                request("Account/auth-key?authKeyId=" + str(key_id), method="DELETE")
                if any(k["id"] == key_id for k in request("Account/auth-keys")):
                    raise RuntimeError("Temporary Auth Key still exists")
                report["temporaryKeyDeleted"] = True
                print("Temporary Auth Key deleted.", flush=True)
            except Exception as error:
                report["temporaryKeyDeleted"] = False
                report["cleanupErrorType"] = type(error).__name__
                print("Temporary Auth Key cleanup failed; it expires within two hours.")
                result = 1
        (artifacts / "session-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return result


if __name__ == "__main__":
    sys.exit(main())
