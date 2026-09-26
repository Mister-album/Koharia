package com.davemorrissey.labs.subscaleview;

/** Balanced source intervals with a bounded decoded size, including the final interval. */
public final class TileGrid {
    private TileGrid() {}

    public static int count(int sourceLength, int maxDecodedLength, int sampleSize) {
        if (sourceLength <= 0 || maxDecodedLength <= 0 || sampleSize <= 0) {
            throw new IllegalArgumentException("Tile dimensions and sample size must be positive");
        }
        long sourceSpan = (long) maxDecodedLength * sampleSize;
        return (int) ((sourceLength + sourceSpan - 1) / sourceSpan);
    }

    public static int boundary(int index, int count, int sourceLength) {
        return (int) ((long) index * sourceLength / count);
    }
}
