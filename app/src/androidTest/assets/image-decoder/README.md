These small synthetic fixtures are generated for decoder regression tests; they contain no user media.

The RGB image is 64x48, with a white border and a solid RGB(32,96,160) rectangle
at (8,8)-(55,39). JPEG, PNG, WebP, HEIC, AVIF and lossless JXL encode the same image.
The RGB fixtures carry an sRGB ICC profile. The alpha fixtures have alpha 128;
the grayscale and CMYK fixtures are conversions of the same RGB source.
The untagged CMYK fixture uses the decoder's USWebCoatedSWOP fallback, not an
RGB arithmetic conversion: an independent Pillow ImageCms conversion of its
CMYK(223,159,95,0) center yields sRGB(54,102,133), perceptual intent.
The odd HEIC/AVIF fixtures are 17x13 with a rectangle at (4,4)-(12,8), exercising
padded scanlines and nonzero region offsets.
The 17x5 palette fixtures repeat indices 0..16 on each row, using
RGB(i,(i*7+3)%256,(i*13+9)%256); palette-alpha uses alpha i.

Created using Pillow 12.3.0 / pillow-heif 1.8.1 and FFmpeg's libjxl lossless encoder.
The tests check pixels, dimensions, regions, sampling, ICC conversion and border cropping
through the app's packaged JNI API, and also run against the minified fixture.
