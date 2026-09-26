package com.davemorrissey.labs.subscaleview.decoder;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.function.BooleanSupplier;

/** Optional contract for viewport-calibrated, cancellable region filtering. */
public interface FilteredRegionDecoder extends ImageRegionDecoder {
    boolean isFilteringEnabled();
    boolean shouldFilter(float displayScale);
    Bitmap decodeRegion(Rect region, int sampleSize, float scaleFactor, boolean filter, BooleanSupplier cancelled);

    default Bitmap decodeRegion(Rect region, int sampleSize, float scaleFactor, BooleanSupplier cancelled) {
        return decodeRegion(region, sampleSize, scaleFactor, true, cancelled);
    }
}
