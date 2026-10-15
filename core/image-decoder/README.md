# Image decoder

This module replaces `com.github.mihonapp:image-decoder:e03b81e18a` with a source
build of the same `tachiyomi.decoder` Kotlin/JNI API. The wrapper sources and
consumer keep rules are from that upstream revision (MPL-2.0); Kotlin formatting
follows this repository. All six decoders remain enabled: JPEG, PNG, WebP, HEIF,
AVIF and JPEG XL. The app, core utilities and reader image view use this module.

Local wrapper changes decode HEIF borders from a validated, stride-aware RGBA
plane and use four-byte pixel offsets for HEIF regions. Output is premultiplied
after color conversion to match Android Bitmap/Canvas semantics; JPEG XL requests
straight alpha before that conversion. Device tests assert colors and compositing.
HEIF inputs marked as premultiplied are converted to straight alpha before CMS.

The focused native updates are libpng 1.6.59, libheif 1.23.6 and libde265 1.1.3.
The remaining codec/color dependencies retain their upstream revisions.
`src/main/cpp/sources.cmake` records immutable revisions and archive SHA-256
digests, including JPEG XL's original Highway/Brotli/skcms submodules. CMake only
adjusts libde265's packaging target name to avoid libjpeg-turbo's `dist` target;
it does not patch decoder implementations. Sources and build outputs stay in
Gradle's ignored native build directories. No Android prebuilt binary is checked in.
The app lets Gradle strip this library for packaging; unstripped build outputs
remain available for native diagnostics without shipping their debug sections.

Build with the repository's JDK/SDK/NDK and CMake 3.22.1, plus Python 3.10 or newer
on PATH. CMake fetches pinned sources, Meson and, on Windows, NASM for the AV1/x86
assembly build. On other hosts install NASM on PATH. The first native build needs
network access; subsequent builds reuse downloaded source archives.

```powershell
.\gradlew.bat :core:image-decoder:externalNativeBuildDebug
.\gradlew.bat -PdeviceTestFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
```

`ImageDecoderDeviceTest` checks the packaged library's formats, colors, alpha,
regions, scaling and cropping. Run it only on the isolated fixture, following the
root AGENTS.md installation/data-retention rules; it also supports minified fixtures.
Building all ABIs proves compile/link compatibility, not device execution on each ABI.

Redistribution notices are packaged in `src/main/assets/licenses/image-decoder.txt`.
The pinned source archives, this module's wrapper and build recipes provide the
source needed to rebuild/relink the native library. Preserve those notices and
source availability when distributing the app.
