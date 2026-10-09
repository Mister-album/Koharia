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

static float lanczos3(float x, float, void*) {
    x = std::abs(x);
    if (x < 1e-7f) return 1.0f;
    if (x >= 3.0f) return 0.0f;
    constexpr double pi = 3.14159265358979323846;
    const double p = pi * x;
    return static_cast<float>(3.0 * std::sin(p) * std::sin(p / 3.0) / (p * p));
}

static float lanczosSupport(float, void*) { return 3.0f; }

static float kernelValue(int kernel, float x, float scale) {
    switch (kernel) {
        case 1: return stbir__filter_catmullrom(x, scale, nullptr);
        case 2: return stbir__filter_triangle(x, scale, nullptr);
        case 3: return lanczos3(x, scale, nullptr);
        default: return stbir__filter_mitchell(x, scale, nullptr);
    }
}

// Convolve the reconstruction kernel with a truncated Gaussian in output-pixel coordinates.
// Simpson quadrature avoids an extra image pass and never modifies shared decoded bands.
struct FilterOptions {
    int kernel;
    float sigma;
    float weights[25];

    FilterOptions(int k, float s) : kernel(k), sigma(s) {
        float total = 0;
        for (int i = 0; i < 25; ++i) {
            float t = (i - 12) * 0.25f;
            weights[i] = std::exp(-0.5f * t * t) * ((i == 0 || i == 24) ? 1 : ((i & 1) ? 4 : 2));
            total += weights[i];
        }
        for (float &weight : weights) weight /= total;
    }
};

static float softenedCubic(float x, float scale, void* data) {
    const auto& options = *static_cast<FilterOptions*>(data);
    float value = 0;
    for (int i = 0; i < 25; ++i) {
        float position = x - (i - 12) * 0.25f * options.sigma;
        value += options.weights[i] * kernelValue(options.kernel, position, scale);
    }
    return value;
}

static float softenedSupport(float, void* data) {
    const auto& options = *static_cast<FilterOptions*>(data);
    return (options.kernel == 2 ? 1.0f : options.kernel == 3 ? 3.0f : 2.0f) + 3.0f * options.sigma;
}

// image-decoder writes straight RGBA into a Bitmap marked as premultiplied.
extern "C" JNIEXPORT jboolean JNICALL
Java_koharia_reader_resampling_ImageResampler_premultiply(JNIEnv* env, jclass, jobject bitmap) {
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
Java_koharia_reader_resampling_ImageResampler_resizeNative(
    JNIEnv* env, jclass, jobject input, jobject output,
    jdouble left, jdouble top, jdouble right, jdouble bottom,
    jint x, jint y, jint width, jint height, jint kernel, jdouble softening) {
    AndroidBitmapInfo src{}, dst{};
    if (AndroidBitmap_getInfo(env, input, &src) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output, &dst) != ANDROID_BITMAP_RESULT_SUCCESS ||
        src.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || dst.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        !std::isfinite(left) || !std::isfinite(top) || !std::isfinite(right) || !std::isfinite(bottom) ||
        kernel < 0 || kernel > 3 || !std::isfinite(softening) || softening < 0 || softening > 1 ||
        right <= left || bottom <= top || x < 0 || y < 0 || width <= 0 || height <= 0 ||
        int64_t(x) + width > dst.width || int64_t(y) + height > dst.height) return JNI_FALSE;
    Pixels a(env, input), b(env, output);
    if (!a.data || !b.data) return JNI_FALSE;
    try {
        STBIR_RESIZE resize;
        auto* target = static_cast<uint8_t*>(b.data) + size_t(y) * dst.stride + size_t(x) * 4;
        stbir_resize_init(&resize, a.data, src.width, src.height, src.stride,
                          target, width, height, dst.stride, STBIR_RGBA_PM, STBIR_TYPE_UINT8);
        FilterOptions options(kernel, static_cast<float>(softening));
        if (softening == 0 && kernel == 3) {
            stbir_set_filter_callbacks(&resize, lanczos3, lanczosSupport, lanczos3, lanczosSupport);
        } else if (softening == 0) {
            auto filter = kernel == 2 ? STBIR_FILTER_TRIANGLE :
                kernel == 1 ? STBIR_FILTER_CATMULLROM : STBIR_FILTER_MITCHELL;
            stbir_set_filters(&resize, filter, filter);
        } else {
            stbir_set_user_data(&resize, &options);
            stbir_set_filter_callbacks(&resize, softenedCubic, softenedSupport, softenedCubic, softenedSupport);
        }
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
