# Mitchell resampling

`stb_image_resize2.h` is header v2.18 from nothings/stb commit
`2c980bb59875b0d32144a71867fbdebb2f77cd20`. Its MIT/public-domain license is included
at the end of the header. JNI uses explicit Mitchell filters, clamped image edges,
and premultiplied RGBA byte channels (no additional gamma conversion).

Local patch: sampler sharing additionally requires equal input dimensions.
With subrects, equal scales, shifts and output dimensions do not imply equal
input bounds. Copying the horizontal sampler into the vertical sampler could
read beyond the final input row (e.g. 268x262 input, 128x128 output at 50%).
