#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
template <typename T> T api(const char* name) {
    // The pinned PdfiumAndroid core loads this same library before this bridge is used.
    static void* library = dlopen("libpdfium.so", RTLD_NOW | RTLD_LOCAL);
    auto address = library ? dlsym(library, name) : nullptr;
    if (!address) throw std::runtime_error(std::string("Missing PDFium API: ") + name);
    return reinterpret_cast<T>(address);
}
void fail(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_koharia_pdf_extraction_PdfiumStyleBridge_glyphs(JNIEnv* env, jobject, jlong handle, jint count) {
    try {
        if (!handle || count < 0 || count > 100000) throw std::runtime_error("Invalid PDF text page");
        auto unicode = api<unsigned int (*)(void*, int)>("FPDFText_GetUnicode");
        auto size = api<double (*)(void*, int)>("FPDFText_GetFontSize");
        auto box = api<int (*)(void*, int, double*, double*, double*, double*)>("FPDFText_GetCharBox");
        auto weight = api<int (*)(void*, int)>("FPDFText_GetFontWeight");
        auto color = api<int (*)(void*, int, unsigned int*, unsigned int*, unsigned int*, unsigned int*)>("FPDFText_GetFillColor");
        auto info = api<unsigned long (*)(void*, int, void*, unsigned long, int*)>("FPDFText_GetFontInfo");
        auto angle = api<float (*)(void*, int)>("FPDFText_GetCharAngle");
        auto page = reinterpret_cast<void*>(static_cast<intptr_t>(handle));
        std::vector<double> result(static_cast<size_t>(count) * 10);
        for (int i = 0; i < count; ++i) {
            double l=0, r=0, b=0, t=0;
            unsigned int red=0, green=0, blue=0, alpha=0;
            int flags=0;
            bool bounds = box(page, i, &l, &r, &b, &t);
            bool fill = color(page, i, &red, &green, &blue, &alpha);
            info(page, i, nullptr, 0, &flags);
            size_t offset = static_cast<size_t>(i) * 10;
            result[offset] = unicode(page, i);
            result[offset+1] = size(page, i);
            result[offset+2] = l; result[offset+3] = b;
            result[offset+4] = r; result[offset+5] = t;
            result[offset+6] = weight(page, i);
            result[offset+7] = fill ? static_cast<double>((alpha<<24)|(red<<16)|(green<<8)|blue) : -1;
            result[offset+8] = flags;
            result[offset+9] = bounds ? angle(page, i) : -1;
        }
        auto array = env->NewDoubleArray(static_cast<jsize>(result.size()));
        if (array) env->SetDoubleArrayRegion(array, 0, static_cast<jsize>(result.size()), result.data());
        return array;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_koharia_pdf_extraction_PdfiumStyleBridge_fontNameBytes(JNIEnv* env, jobject, jlong handle, jint index) {
    try {
        auto info = api<unsigned long (*)(void*, int, void*, unsigned long, int*)>("FPDFText_GetFontInfo");
        auto page = reinterpret_cast<void*>(static_cast<intptr_t>(handle));
        auto length = info(page, index, nullptr, 0, nullptr);
        if (length == 0 || length > 4096) return nullptr;
        std::vector<char> name(length + 1, 0);
        info(page, index, name.data(), length, nullptr);
        auto result = env->NewByteArray(static_cast<jsize>(length - 1));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(length - 1), reinterpret_cast<jbyte*>(name.data()));
        return result;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_koharia_pdf_extraction_PdfiumStyleBridge_graphics(JNIEnv* env, jobject, jlong handle) {
    try {
        auto count = api<int (*)(void*)>("FPDFPage_CountObjects");
        auto get = api<void* (*)(void*, int)>("FPDFPage_GetObject");
        auto type = api<int (*)(void*)>("FPDFPageObj_GetType");
        auto bounds = api<int (*)(void*, float*, float*, float*, float*)>("FPDFPageObj_GetBounds");
        auto formCount = api<int (*)(void*)>("FPDFFormObj_CountObjects");
        auto page = reinterpret_cast<void*>(static_cast<intptr_t>(handle));
        std::vector<float> result;
        int total = count(page);
        if (total < 0 || total > 100000) throw std::runtime_error("Invalid PDF object count");
        for (int i = 0; i < total; ++i) {
            auto object = get(page, i);
            int kind = type(object);
            if (kind == 1) continue;
            float l=0,b=0,r=0,t=0;
            if (bounds(object,&l,&b,&r,&t)) {
                // Forms are deliberately retained as visual regions; their nested transforms need not be reconstructed.
                if (kind != 5 || formCount(object) > 0)
                    result.insert(result.end(), {static_cast<float>(kind), l,b,r,t});
            }
        }
        auto array = env->NewFloatArray(static_cast<jsize>(result.size()));
        if (array) env->SetFloatArrayRegion(array, 0, static_cast<jsize>(result.size()), result.data());
        return array;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_koharia_pdf_extraction_PdfiumStyleBridge_annotationBoxes(JNIEnv* env, jobject, jlong handle) {
    try {
        auto count = api<int (*)(void*)>("FPDFPage_GetAnnotCount");
        auto get = api<void* (*)(void*, int)>("FPDFPage_GetAnnot");
        auto close = api<void (*)(void*)>("FPDFPage_CloseAnnot");
        auto type = api<int (*)(void*)>("FPDFAnnot_GetSubtype");
        struct Rect { float left, top, right, bottom; };
        auto rect = api<int (*)(void*, Rect*)>("FPDFAnnot_GetRect");
        auto page = reinterpret_cast<void*>(static_cast<intptr_t>(handle));
        int total = count(page);
        if (total < 0 || total > 10000) throw std::runtime_error("Invalid PDF annotation count");
        std::vector<float> result;
        for (int i = 0; i < total; ++i) {
            auto annotation = get(page, i);
            if (!annotation) continue;
            int kind = type(annotation);
            Rect box{};
            bool keep = (kind == 1 || kind == 3 || (kind >= 9 && kind <= 12)) && rect(annotation, &box);
            close(annotation);
            if (keep) result.insert(result.end(), {static_cast<float>(i), box.left, box.bottom, box.right, box.top});
        }
        auto array = env->NewFloatArray(static_cast<jsize>(result.size()));
        if (array) env->SetFloatArrayRegion(array, 0, static_cast<jsize>(result.size()), result.data());
        return array;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_koharia_pdf_extraction_PdfiumStyleBridge_annotationText(JNIEnv* env, jobject, jlong handle, jint index) {
    try {
        auto get = api<void* (*)(void*, int)>("FPDFPage_GetAnnot");
        auto close = api<void (*)(void*)>("FPDFPage_CloseAnnot");
        auto text = api<unsigned long (*)(void*, const char*, void*, unsigned long)>("FPDFAnnot_GetStringValue");
        auto page = reinterpret_cast<void*>(static_cast<intptr_t>(handle));
        auto annotation = get(page, index);
        if (!annotation) return nullptr;
        auto length = text(annotation, "Contents", nullptr, 0);
        if (length < 2 || length > 200000) { close(annotation); return nullptr; }
        std::vector<unsigned char> bytes;
        try { bytes.resize(length); } catch (...) { close(annotation); throw; }
        text(annotation, "Contents", bytes.data(), length);
        close(annotation);
        auto array = env->NewByteArray(static_cast<jsize>(length));
        if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(length), reinterpret_cast<jbyte*>(bytes.data()));
        return array;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}
