#include "../dig_detector.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
void spot(std::vector<std::uint8_t>& image, int w, int x, int y, int value = 130) {
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx)
        image[(y + dy) * w + x + dx] = static_cast<std::uint8_t>(value - 18 * (std::abs(dx) + std::abs(dy)));
}
bool near(const prism::DigResult& r, double x, double y, double tolerance = 3) {
    return std::any_of(r.candidates.begin(), r.candidates.end(), [&](auto c) {
        return std::hypot(c.x - x, c.y - y) < tolerance;
    });
}
}

int main(int argc, char** argv) {
    try {
        if (argc == 2) {
            std::ifstream input(argv[1], std::ios::binary);
            std::string magic; int w, h, maximum;
            input >> magic >> w >> h >> maximum;
            check(magic == "P5" && maximum == 255 && w > 0 && h > 0, "Expected 8-bit PGM");
            input.get(); std::vector<std::uint8_t> image(w * h);
            input.read(reinterpret_cast<char*>(image.data()), image.size());
            check(static_cast<std::size_t>(input.gcount()) == image.size(), "Truncated PGM");
            auto r = prism::detect_digs({image.data(), w, h, w, 1});
            std::cout << "{\"score\":" << r.score << ",\"maskedFraction\":" << r.masked_fraction
                      << ",\"contrastThreshold\":" << r.contrast_threshold << ",\"candidates\":[";
            bool first = true;
            for (auto c : r.candidates) {
                if (!first) std::cout << ',';
                first = false;
                std::cout << "{\"x\":" << c.x << ",\"y\":" << c.y << ",\"score\":" << c.score << ",\"area\":" << c.area << '}';
            }
            std::cout << "]}\n"; return 0;
        }
        constexpr int w = 160, h = 140;
        std::vector<std::uint8_t> image(w * h, 28);
        auto run = [&] { return prism::detect_digs({image.data(), w, h, w, 1}); };
        check(run().candidates.empty(), "Uniform dark plane produced candidates");
        image[70 * w + 80] = 220;
        check(run().candidates.empty(), "Isolated hot pixel accepted");
        std::fill(image.begin(), image.end(), 28); spot(image, w, 45, 60); spot(image, w, 110, 85);
        auto two = run();
        check(two.candidates.size() == 2 && near(two, 45, 60) && near(two, 110, 85), "Two compact spots not located");
        check(two.score > 0, "Spot score missing");
        std::fill(image.begin(), image.end(), 28);
        for (int x = 20; x < 135; ++x) image[70 * w + x] = 160;
        check(run().candidates.empty(), "Long reflection line classified as point");
        std::fill(image.begin(), image.end(), 28);
        for (int i = 20; i < 120; ++i) image[i * w + i] = 160;
        check(run().candidates.empty(), "Diagonal scratch classified as point");
        std::fill(image.begin(), image.end(), 28);
        for (int y = 20; y < 40; ++y) for (int x = 0; x < w; ++x) image[y * w + x] = 250;
        auto band = run();
        check(band.candidates.empty() && band.masked_fraction > .1, "Glare band was not masked");
        spot(image, w, 80, 95);
        check(near(run(), 80, 95), "Point beside glare band was lost");
        std::fill(image.begin(), image.end(), 28);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) image[y * w + x] = 20 + x / 4 + y / 5;
        check(run().candidates.empty(), "Smooth gradient produced candidates");
        for (int i = 0; i < w * h; ++i) image[i] = 28 + ((i * 17 + i / w * 13) % 5) - 2;
        check(run().candidates.empty(), "Low camera noise produced candidates");
        std::fill(image.begin(), image.end(), 28); spot(image, w, 7, 70);
        check(run().candidates.empty(), "ROI boundary component accepted");
        std::fill(image.begin(), image.end(), 28); spot(image, w, 70, 70);
        auto reference = run();
        std::vector<std::uint8_t> padded((w * 2 + 32) * h, 0);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) padded[y * (w * 2 + 32) + x * 2] = image[y * w + x];
        auto strides = prism::detect_digs({padded.data(), w, h, w * 2 + 32, 2});
        check(strides.candidates.size() == reference.candidates.size() && std::abs(strides.score - reference.score) < 1e-8,
              "Row/pixel stride changed result");
        check(prism::detect_digs({nullptr, w, h, w, 1}).candidates.empty(), "Null plane accepted");
        check(prism::detect_digs({image.data(), w, h, w - 1, 1}).candidates.empty(), "Invalid row stride accepted");
        auto start = std::chrono::steady_clock::now();
        for (int i = 0; i < 100; ++i) run();
        auto ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        std::cout << "PASS native regression scenarios; mean host analysis " << ms / 100 << " ms at 160x140 (not phone FPS).\n";
    } catch (const std::exception& e) { std::cerr << "FAIL: " << e.what() << '\n'; return 1; }
}
