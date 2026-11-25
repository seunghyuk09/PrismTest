#include <android/bitmap.h>
#include <jni.h>
#include <algorithm>
#include <cstdint>

namespace {
void invalid(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
std::uint8_t channel(int value) { return static_cast<std::uint8_t>(std::clamp(value, 0, 255)); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_prismtest_scope_NativeColor_render(JNIEnv* env, jobject, jbyteArray ys, jbyteArray us,
        jbyteArray vs, jint width, jint height, jint cw, jint ch, jint phase_x, jint phase_y,
        jint rotation, jint matrix, jboolean full_range, jobject output) {
    const bool color = us != nullptr && vs != nullptr;
    const auto count = static_cast<std::int64_t>(width) * height;
    if (!ys || width <= 0 || height <= 0 || count > 16777216 ||
        env->GetArrayLength(ys) != count || (us == nullptr) != (vs == nullptr) ||
        (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) ||
        matrix < 0 || matrix > 2 || phase_x < 0 || phase_x > 1 || phase_y < 0 || phase_y > 1) {
        invalid(env, "Invalid color frame dimensions"); return;
    }
    if (color && (cw != (width + phase_x + 1) / 2 || ch != (height + phase_y + 1) / 2 ||
        env->GetArrayLength(us) != static_cast<std::int64_t>(cw) * ch ||
        env->GetArrayLength(vs) != static_cast<std::int64_t>(cw) * ch)) {
        invalid(env, "Invalid chroma plane dimensions"); return;
    }
    const bool swap = rotation == 90 || rotation == 270;
    const int dw = swap ? height : width, dh = swap ? width : height;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, output, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width != static_cast<unsigned>(dw) ||
        info.height != static_cast<unsigned>(dh) || info.stride < static_cast<unsigned>(dw) * 4) {
        invalid(env, "Invalid destination bitmap"); return;
    }
    void* address = nullptr;
    if (AndroidBitmap_lockPixels(env, output, &address) != ANDROID_BITMAP_RESULT_SUCCESS || !address) {
        invalid(env, "Cannot access destination bitmap"); return;
    }
    auto* y_data = reinterpret_cast<const std::uint8_t*>(env->GetByteArrayElements(ys, nullptr));
    const std::uint8_t *u_data = nullptr, *v_data = nullptr;
    if (y_data && color) u_data = reinterpret_cast<const std::uint8_t*>(env->GetByteArrayElements(us, nullptr));
    if (u_data) v_data = reinterpret_cast<const std::uint8_t*>(env->GetByteArrayElements(vs, nullptr));
    if (y_data && (!color || (u_data && v_data))) {
        // Integer SDR YUV conversion: standard-specific coefficients and explicit range.
        const int y_scale = full_range ? 256 : 298;
        const int red[2][3] = {{409, 459, 430}, {359, 403, 377}};
        const int green_u[2][3] = {{100, 55, 48}, {88, 48, 42}};
        const int green_v[2][3] = {{208, 136, 167}, {183, 120, 146}};
        const int blue[2][3] = {{516, 541, 548}, {454, 475, 482}};
        const int range = full_range ? 1 : 0;
        auto* pixels = static_cast<std::uint8_t*>(address);
        for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
            const int luminance = y_data[static_cast<std::size_t>(y) * width + x];
            int r = luminance, g = luminance, b = luminance;
            if (color) {
                const auto at = static_cast<std::size_t>((y + phase_y) / 2) * cw + (x + phase_x) / 2;
                const int u = static_cast<int>(u_data[at]) - 128, v = static_cast<int>(v_data[at]) - 128;
                const int c = y_scale * (full_range ? luminance : std::max(0, luminance - 16));
                r = (c + red[range][matrix] * v + 128) >> 8;
                g = (c - green_u[range][matrix] * u - green_v[range][matrix] * v + 128) >> 8;
                b = (c + blue[range][matrix] * u + 128) >> 8;
            }
            int dx = x, dy = y;
            if (rotation == 90) { dx = height - 1 - y; dy = x; }
            else if (rotation == 180) { dx = width - 1 - x; dy = height - 1 - y; }
            else if (rotation == 270) { dx = y; dy = width - 1 - x; }
            auto* p = pixels + static_cast<std::size_t>(dy) * info.stride + static_cast<std::size_t>(dx) * 4;
            p[0] = channel(r); p[1] = channel(g); p[2] = channel(b); p[3] = 255;
        }
    }
    if (v_data) env->ReleaseByteArrayElements(vs, reinterpret_cast<jbyte*>(const_cast<std::uint8_t*>(v_data)), JNI_ABORT);
    if (u_data) env->ReleaseByteArrayElements(us, reinterpret_cast<jbyte*>(const_cast<std::uint8_t*>(u_data)), JNI_ABORT);
    if (y_data) env->ReleaseByteArrayElements(ys, reinterpret_cast<jbyte*>(const_cast<std::uint8_t*>(y_data)), JNI_ABORT);
    AndroidBitmap_unlockPixels(env, output);
    if ((!y_data || (color && (!u_data || !v_data))) && !env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Cannot access color source planes");
    }
}
