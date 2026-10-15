"""Build the pinned dav1d source with the same NDK/ABI as the JNI target."""
import os
import subprocess
import sys
from pathlib import Path

cc, ar, strip, sysroot, abi, api, source, build, meson, ninja, nasm = sys.argv[1:]
triple, family, cpu = {
    "arm64-v8a": ("aarch64", "aarch64", "armv8"),
    "armeabi-v7a": ("armv7a", "arm", "armv7"),
    "x86": ("i686", "x86", "i686"),
    "x86_64": ("x86_64", "x86_64", "x86_64"),
}[abi]
build = Path(build)
build.mkdir(parents=True, exist_ok=True)
cross = build / "android-cross.ini"
cross.write_text(
    f"""[binaries]
c = ['{cc}', '--target={triple}-linux-android{api}', '--sysroot={sysroot}']
ar = '{ar}'
strip = '{strip}'
[host_machine]
system = 'android'
cpu_family = '{family}'
cpu = '{cpu}'
endian = 'little'
[properties]
needs_exe_wrapper = true
""",
    encoding="utf-8",
)
env = dict(os.environ)
env["PATH"] = os.pathsep.join([str(Path(ninja).parent), str(Path(nasm).parent), env["PATH"]])
env["NINJA"] = ninja
command = [sys.executable, meson, "setup", str(build), source, "--cross-file", str(cross),
           "--buildtype=release", "--default-library=static", "-Denable_tools=false", "-Denable_tests=false"]
if (build / "build.ninja").exists():
    command.append("--reconfigure")
subprocess.run(command, env=env, check=True)
subprocess.run([ninja, "-C", str(build), "-j2"], env=env, check=True)
