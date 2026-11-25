#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace prism {
struct GrayView {
    const std::uint8_t* data;
    int width, height, row_stride, pixel_stride;
};
struct DigCandidate {
    double x, y, radius, contrast, score;
    int area;
};
struct DigResult {
    std::vector<DigCandidate> candidates;
    double score = 0;
    double masked_fraction = 0;
    double contrast_threshold = 0;
};
// Bright compact scatter candidates, not physical size measurements or a material diagnosis.
// The core deliberately has no Android dependency; host tests use this exact implementation.
DigResult detect_digs(GrayView image);
}
