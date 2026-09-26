#include <jni.h>
#include <android/bitmap.h>
#include <cmath>
#include <cstdint>
#include <new>
#define STB_IMAGE_RESIZE_IMPLEMENTATION
#include "stb_image_resize2.h"

class Pixels {
    JNIEnv* env;
    jobject bitmap;
public:
    void* data = nullptr;
    Pixels(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_lockPixels(env, bitmap, &data) != ANDROID_BITMAP_RESULT_SUCCESS) data = nullptr;
    }
    ~Pixels() { if (data) AndroidBitmap_unlockPixels(env, bitmap); }
};

// image-decoder writes straight RGBA into a Bitmap marked as premultiplied.
extern "C" JNIEXPORT jboolean JNICALL
Java_koharia_reader_resampling_MitchellResampler_premultiply(JNIEnv* env, jclass, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    Pixels pixels(env, bitmap);
    if (!pixels.data) return JNI_FALSE;
    for (uint32_t y = 0; y < info.height; ++y) {
        auto* row = static_cast<uint8_t*>(pixels.data) + size_t(y) * info.stride;
        for (uint32_t x = 0; x < info.width; ++x, row += 4) {
            for (int c = 0; c < 3; ++c) row[c] = (uint32_t(row[c]) * row[3] + 127) / 255;
        }
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_koharia_reader_resampling_MitchellResampler_resize(
    JNIEnv* env, jclass, jobject input, jobject output,
    jdouble left, jdouble top, jdouble right, jdouble bottom,
    jint x, jint y, jint width, jint height) {
    AndroidBitmapInfo src{}, dst{};
    if (AndroidBitmap_getInfo(env, input, &src) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output, &dst) != ANDROID_BITMAP_RESULT_SUCCESS ||
        src.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || dst.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        !std::isfinite(left) || !std::isfinite(top) || !std::isfinite(right) || !std::isfinite(bottom) ||
        right <= left || bottom <= top || x < 0 || y < 0 || width <= 0 || height <= 0 ||
        int64_t(x) + width > dst.width || int64_t(y) + height > dst.height) return JNI_FALSE;
    Pixels a(env, input), b(env, output);
    if (!a.data || !b.data) return JNI_FALSE;
    try {
        STBIR_RESIZE resize;
        auto* target = static_cast<uint8_t*>(b.data) + size_t(y) * dst.stride + size_t(x) * 4;
        stbir_resize_init(&resize, a.data, src.width, src.height, src.stride,
                          target, width, height, dst.stride, STBIR_RGBA_PM, STBIR_TYPE_UINT8);
        stbir_set_filters(&resize, STBIR_FILTER_MITCHELL, STBIR_FILTER_MITCHELL);
        if (!stbir_set_input_subrect(&resize, left / src.width, top / src.height, right / src.width, bottom / src.height) ||
            !stbir_resize_extended(&resize)) return JNI_FALSE;
        for (int row = 0; row < height; ++row) {
            auto* pixel = target + size_t(row) * dst.stride;
            for (int column = 0; column < width; ++column, pixel += 4) {
                for (int c = 0; c < 3; ++c) if (pixel[c] > pixel[3]) pixel[c] = pixel[3];
            }
        }
        return JNI_TRUE;
    } catch (const std::bad_alloc&) {
        return JNI_FALSE;
    }
}
