package com.davemorrissey.labs.subscaleview.decoder;

public interface RegionDecoderFactory {
    ImageRegionDecoder create(boolean cropBorders, boolean hardwareConfig, byte[] displayProfile);
}
