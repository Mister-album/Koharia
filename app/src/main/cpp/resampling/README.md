# Reader image resampling

`stb_image_resize2.h` is header v2.18 from nothings/stb commit
`2c980bb59875b0d32144a71867fbdebb2f77cd20`. Its MIT/public-domain license is included
at the end of the header. JNI defaults to Mitchell (B=C=1/3), with a
Catmull–Rom option (B=0, C=1/2). Both use clamped image edges and premultiplied
RGBA byte channels (no additional gamma conversion). Bilinear uses STB's triangle
filter (with widened support on reduction); Lanczos3 uses a local normalized
three-lobe sinc callback. Native IDs are explicit: Mitchell 0, Catmull–Rom 1,
Bilinear 2, Lanczos3 3. Do not use enum ordinal values in the JNI interface.

Reader settings expose Mitchell compatibility mode, Bilinear, Bicubic
(Catmull–Rom), and Lanczos3. All kernels apply to reduction and enlargement; exact original
size bypasses resampling. Unset reader settings default to Bicubic (Catmull–Rom)
and balanced quality, with the main switch still off. Explicit saved choices are
preserved; unknown saved kernel IDs fall back to compatibility mode without
rewriting their values. Legacy thresholds remain stored but are no longer read or applied.

Quality is independent of the kernel. Speed, balanced, and detail retain at
least 1, 2, and 4 decoded input samples per output pixel in each dimension when
possible, using power-of-two native decoding. Enlargement always uses sample 1.
Codec pre-sampling can discard detail, especially PNG's skipped scanlines; the
detail preset is not a promise of lower moire. Balanced retains the old policy.
Sampling policy, kernel support, and execution limits are separate so benchmarks
can tune them independently without changing the saved option identifiers.

The experimental softness parameter is Gaussian sigma in output pixels, from
0 to 1. A cubic kernel is convolved with a Gaussian truncated at 3 sigma using
25-point Simpson quadrature. STB evaluates the combined kernel in one resize,
without another full image or blur pass. Zero softness selects the unmodified
kernel. Softness remains an instrumentation option, not a reader setting.

`RegionResamplingPlan` derives the halo from kernel support, decode sampling,
and final scale, including full source-pixel support during enlargement. A
single image-wide output grid preserves phase across tiles. Upscaled detail
grids use power-of-two scale budgets and never allocate a full enlarged image.
`ResamplingBudget` bounds inputs to 2 Mi pixels, outputs to 1 Mi pixels, internal
blocks to 512 pixels per edge, and each decoder's PNG cache to the smaller of
heap/16 and 32 MiB. Native processing shares two worker permits. Codec-internal
memory and simultaneously displayed/pending layers are additional allocations.
The injectable shared `ResamplingScheduler` permits one or two workers; production
uses two. Non-legacy PNG block heights also fit the strip cache, including an
alignment band, to avoid repeatedly evicting and decoding the same scanlines.
Mitchell/balanced retains its previous block boundaries for pixel compatibility.
JNI callback options are local to each call. Configuration changes prepare an
immutable decoder and retain the viewport while publishing complete layers;
obsolete generations cannot publish after a switch.

Controlled device benchmarks can inject `ResamplingTimings` to separate encoded
input copying, codec setup, decoding, premultiplication, resizing, codec release,
and scheduler waiting. Executor queue time is measured separately from permit
waiting, starting when each tile task is constructed and ending when its worker
starts; it also includes tasks later discarded as obsolete. These cumulative
durations can overlap and must not be added as an end-to-end breakdown.
Normal readers leave diagnostics unset. The screen fixture
accepts `moireProfile=true`, `moireKernel` (including `OFF`), and
`moireCompetingPages=0..2`; competing pages are copies of the same input beneath
the measured view, not an end-to-end reader benchmark. Capture validation requires
current revisions and a processed detail layer while allowing a direct 1:1 base.

Input-reuse experiments can inject `ResamplingBudget.reuseJpegBands` and
`pngBandRows`; the screen and large-page fixtures expose these as
`moireReuseJpegBands=true` and `moirePngBandRows=1..1024`. Defaults remain JPEG
regional decoding and 256-row PNG bands. JPEG reuse applies only to downscaling
when the sampled page fits the existing cache budget; enlargement and Mitchell
retain regional decoding. Mitchell always keeps 256-row PNG bands. Larger bands
still obey the input-pixel and byte budgets. Compare pixels, completion times,
and retained memory before changing production defaults.

For compiler-cost comparisons, build with `-PdeviceTestFixture=true
-PresamplingBenchmarkOptimized=true`. This changes only the resampling native
module to `-O2`, retains `-ffp-contract=off`, and is rejected outside the isolated
fixture. Normal dev builds enable the same resampling-only optimization by
default; other native modules and release build settings are unchanged. Fixture
builds keep the normal NDK debug setting unless the benchmark flag is supplied,
so unoptimized comparisons remain reproducible. Compare pixels as well as timings
before adopting compiler or execution-budget changes.

Disabled static-image decoders retain the compressed source and use the original
native decoder, allowing interpolation to be enabled without reopening a reader
page or losing its viewport. Animated, bitmap-rendered document pages and
Coil-only fallback images do not go through this static region pipeline.

Local patch: sampler sharing additionally requires equal input dimensions.
With subrects, equal scales, shifts and output dimensions do not imply equal
input bounds. Copying the horizontal sampler into the vertical sampler could
read beyond the final input row (e.g. 268x262 input, 128x128 output at 50%).
