package com.davemorrissey.labs.subscaleview.decoder;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.function.BooleanSupplier;

/** Optional contract for viewport-calibrated, cancellable region filtering. */
public interface FilteredRegionDecoder extends ImageRegionDecoder {
    boolean isFilteringEnabled();
    boolean shouldFilter(float displayScale);
    Bitmap decodeRegion(Rect region, int sampleSize, float scaleFactor, boolean filter, BooleanSupplier cancelled);

    /** Opt-in diagnostics; normal readers do not time the executor queue. */
    default boolean recordsTaskQueueTiming() { return false; }
    default void recordTaskQueueWait(long elapsedNanos) { }

    /** Actual outcome, including a recoverable raw fallback. */
    final class Result {
        public final Bitmap bitmap;
        public final boolean filtered;

        public Result(Bitmap bitmap, boolean filtered) {
            this.bitmap = bitmap;
            this.filtered = filtered;
        }
    }

    default Result decodeResult(Rect region, int sampleSize, float scaleFactor, boolean filter, BooleanSupplier cancelled) {
        return new Result(decodeRegion(region, sampleSize, scaleFactor, filter, cancelled), filter);
    }

    default Bitmap decodeRegion(Rect region, int sampleSize, float scaleFactor, BooleanSupplier cancelled) {
        return decodeRegion(region, sampleSize, scaleFactor, true, cancelled);
    }
}
