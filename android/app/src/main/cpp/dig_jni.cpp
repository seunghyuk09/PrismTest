#include "dig_detector.h"
#include <jni.h>
#include <cstdint>
#include <vector>
#include <new>

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_prismtest_scope_NativeDig_analyze(JNIEnv* env, jobject, jobject buffer,
        jint offset, jint row_stride, jint pixel_stride, jint width, jint height) {
    auto* data = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(buffer));
    auto capacity = env->GetDirectBufferCapacity(buffer);
    const std::int64_t end = static_cast<std::int64_t>(offset) +
        static_cast<std::int64_t>(height - 1) * row_stride +
        static_cast<std::int64_t>(width - 1) * pixel_stride;
    if (!data || offset < 0 || width <= 0 || height <= 0 || row_stride <= 0 || pixel_stride <= 0 ||
            end < 0 || end >= capacity) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(error, "Invalid luminance plane bounds"); return nullptr;
    }
    try {
        auto result = prism::detect_digs({data + offset, width, height, row_stride, pixel_stride});
        std::vector<jdouble> values{result.score, result.masked_fraction, result.contrast_threshold,
                                   static_cast<double>(result.candidates.size())};
        for (const auto& c : result.candidates) {
            values.insert(values.end(), {c.x, c.y, c.radius, c.contrast, static_cast<double>(c.area)});
        }
        auto output = env->NewDoubleArray(static_cast<jsize>(values.size()));
        if (output) env->SetDoubleArrayRegion(output, 0, static_cast<jsize>(values.size()), values.data());
        return output;
    } catch (const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Cannot allocate analysis buffers");
        return nullptr;
    }
}
