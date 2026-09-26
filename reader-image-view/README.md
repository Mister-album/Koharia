# Reader image view

Vendored from tachiyomiorg/subsampling-scale-image-view commit
`66e0db195d1e41436b8bc3a22fea551e5d457db8` (Apache-2.0, see LICENSE).
Upstream packages are retained for source compatibility. Production codec versions are unchanged.

Koharia changes: injectable region decoder, a viewport-calibrated tile pyramid for filtered decoding,
display-scale filtering decisions (including enclosing viewer zoom), and generation/visibility
cancellation with stale bitmap/decoder disposal. Filter transitions retain existing tiles until
replacement decoding finishes. Tile counts are computed directly and source intervals are balanced
so the final tile respects the same decoded-size limit as the others, even on very long images.
The default decoder and LOD policy remain available when filtering is disabled. App-specific resampling lives in
`koharia.reader.resampling`, outside the upstream view.
