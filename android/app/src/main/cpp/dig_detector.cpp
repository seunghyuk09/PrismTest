#include "dig_detector.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>

namespace prism {
DigResult detect_digs(GrayView image) {
    DigResult result;
    const int w = image.width, h = image.height, radius = 6;
    if (!image.data || w < 20 || h < 20 || w > 4096 || h > 4096 ||
        image.row_stride <= 0 || image.pixel_stride <= 0 ||
        static_cast<long long>(w - 1) * image.pixel_stride >= image.row_stride) return result;
    const int n = w * h, stride = w + 1;
    auto pixel = [&](int x, int y) { return image.data[y * image.row_stride + x * image.pixel_stride]; };
    std::vector<double> integral(static_cast<std::size_t>(w + 1) * (h + 1), 0);
    for (int y = 0; y < h; ++y) {
        double row = 0;
        for (int x = 0; x < w; ++x) {
            row += pixel(x, y);
            integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row;
        }
    }
    auto box_sum = [&](int l, int t, int r, int b) {
        return integral[b * stride + r] - integral[t * stride + r] -
               integral[b * stride + l] + integral[t * stride + l];
    };

    // Mask large near-white reflections, including their transition edge. A tiny clipped
    // point alone is not a broad glare band, so it is retained as a candidate.
    std::vector<std::uint8_t> seen(n, 0), mask(n, 0);
    std::vector<int> queue;
    queue.reserve(n / 8);
    for (int seed = 0; seed < n; ++seed) {
        if (seen[seed] || pixel(seed % w, seed / w) < 224) continue;
        queue.clear(); queue.push_back(seed); seen[seed] = 1;
        int min_x = seed % w, max_x = min_x;
        for (std::size_t at = 0; at < queue.size(); ++at) {
            int x = queue[at] % w, y = queue[at] / w;
            min_x = std::min(min_x, x); max_x = std::max(max_x, x);
            for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                int next = ny * w + nx;
                if (!seen[next] && pixel(nx, ny) >= 224) { seen[next] = 1; queue.push_back(next); }
            }
        }
        if (queue.size() >= static_cast<std::size_t>(std::max(64, n / 512)) || max_x - min_x > w / 5)
            for (int index : queue) mask[index] = 1;
    }
    std::vector<int> mask_integral(static_cast<std::size_t>(w + 1) * (h + 1), 0);
    for (int y = 0; y < h; ++y) {
        int row = 0;
        for (int x = 0; x < w; ++x) {
            row += mask[y * w + x];
            mask_integral[(y + 1) * stride + x + 1] = mask_integral[y * stride + x + 1] + row;
        }
    }
    int masked = 0;
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        int l = std::max(0, x - radius - 2), r = std::min(w, x + radius + 3);
        int t = std::max(0, y - radius - 2), b = std::min(h, y + radius + 3);
        int sum = mask_integral[b * stride + r] - mask_integral[t * stride + r] -
                  mask_integral[b * stride + l] + mask_integral[t * stride + l];
        mask[y * w + x] = sum > 0;
        masked += mask[y * w + x];
    }
    result.masked_fraction = static_cast<double>(masked) / n;

    // Local annular background removes smooth shading. A robust noise estimate adapts the
    // candidate threshold to camera noise; this is NOT the calibrated PASS/FAIL threshold.
    std::vector<float> response(n, 0);
    std::array<int, 256> noise_hist{};
    int samples = 0;
    constexpr double ring_area = (2 * radius + 1) * (2 * radius + 1) - 9;
    for (int y = radius; y < h - radius; ++y) for (int x = radius; x < w - radius; ++x) {
        int index = y * w + x;
        if (mask[index]) continue;
        double ring = box_sum(x - radius, y - radius, x + radius + 1, y + radius + 1) -
                      box_sum(x - 1, y - 1, x + 2, y + 2);
        double delta = pixel(x, y) - ring / ring_area;
        response[index] = static_cast<float>(delta);
        if ((x & 1) == 0 && (y & 1) == 0) {
            ++noise_hist[std::min(255, static_cast<int>(std::abs(delta)))]; ++samples;
        }
    }
    if (samples == 0) return result;
    int count = 0, noise = 0;
    for (; noise < 255; ++noise) { count += noise_hist[noise]; if (count > samples / 2) break; }
    const double threshold = std::max(10.0, 4.0 * 1.4826 * noise);
    result.contrast_threshold = threshold;
    std::fill(seen.begin(), seen.end(), 0);
    const int max_size = std::max(8, static_cast<int>(std::min(w, h) * 0.07));
    for (int seed = 0; seed < n; ++seed) {
        if (seen[seed] || mask[seed] || response[seed] < threshold) continue;
        queue.clear(); queue.push_back(seed); seen[seed] = 1;
        int l = seed % w, r = l, t = seed / w, b = t;
        double weight = 0, sx = 0, sy = 0, xx = 0, yy = 0, xy = 0, peak = 0;
        for (std::size_t at = 0; at < queue.size(); ++at) {
            int current = queue[at], x = current % w, y = current / w;
            double q = response[current];
            l = std::min(l, x); r = std::max(r, x); t = std::min(t, y); b = std::max(b, y);
            weight += q; sx += x * q; sy += y * q;
            xx += x * x * q; yy += y * y * q; xy += x * y * q; peak = std::max(peak, q);
            for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
                int nx = x + dx, ny = y + dy;
                if (nx < radius || nx >= w - radius || ny < radius || ny >= h - radius) continue;
                int next = ny * w + nx;
                if (!seen[next] && !mask[next] && response[next] >= threshold) {
                    seen[next] = 1; queue.push_back(next);
                }
            }
        }
        int area = static_cast<int>(queue.size()), bw = r - l + 1, bh = b - t + 1;
        if (area < 2 || area > 196 || bw > max_size || bh > max_size ||
            bw > 3 * bh || bh > 3 * bw || area < bw * bh * 0.18 ||
            l <= radius || r >= w - radius - 1 || t <= radius || b >= h - radius - 1) continue;
        double cx = sx / weight, cy = sy / weight;
        double vx = std::max(0.0, xx / weight - cx * cx), vy = std::max(0.0, yy / weight - cy * cy);
        double cov = xy / weight - cx * cy;
        double root = std::sqrt(std::max(0.0, (vx - vy) * (vx - vy) + 4 * cov * cov));
        double major = (vx + vy + root) / 2, minor = (vx + vy - root) / 2;
        if (major > 3 && major / std::max(0.20, minor) > 12.25) continue;
        double score = peak * std::sqrt(static_cast<double>(std::min(area, 16))) / 2;
        result.candidates.push_back({cx, cy, std::max(bw, bh) / 2.0, peak, score, area});
        result.score = std::max(result.score, score);
    }
    std::sort(result.candidates.begin(), result.candidates.end(),
              [](const DigCandidate& a, const DigCandidate& b) { return a.score > b.score; });
    if (result.candidates.size() > 32) result.candidates.resize(32);
    return result;
}
}
