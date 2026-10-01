#include "camylla/virtual_camera/synthetic_frame_provider.h"

#include <array>
#include <stdexcept>

namespace camylla::virtual_camera {
namespace {

constexpr std::array<std::array<std::uint8_t, 5>, 10> kDigits{{
    {{0x1EU, 0x11U, 0x13U, 0x15U, 0x19U}}, {{0x00U, 0x12U, 0x1FU, 0x10U, 0x10U}},
    {{0x1EU, 0x01U, 0x1EU, 0x10U, 0x1FU}}, {{0x1EU, 0x01U, 0x0EU, 0x01U, 0x1EU}},
    {{0x11U, 0x11U, 0x1FU, 0x01U, 0x01U}}, {{0x1FU, 0x10U, 0x1EU, 0x01U, 0x1EU}},
    {{0x0EU, 0x10U, 0x1EU, 0x11U, 0x0EU}}, {{0x1FU, 0x01U, 0x02U, 0x04U, 0x04U}},
    {{0x0EU, 0x11U, 0x0EU, 0x11U, 0x0EU}}, {{0x0EU, 0x11U, 0x0FU, 0x01U, 0x0EU}},
}};

constexpr std::array<std::array<std::uint8_t, 5>, 7> kLetters{{
    {{0x0EU, 0x10U, 0x10U, 0x10U, 0x0EU}},  // C
    {{0x0EU, 0x11U, 0x1FU, 0x11U, 0x11U}},  // A
    {{0x11U, 0x1BU, 0x15U, 0x11U, 0x11U}},  // M
    {{0x11U, 0x0AU, 0x04U, 0x04U, 0x04U}},  // Y
    {{0x10U, 0x10U, 0x10U, 0x10U, 0x1FU}},  // L
    {{0x1EU, 0x11U, 0x1EU, 0x10U, 0x10U}},  // P
    {{0x1EU, 0x11U, 0x11U, 0x11U, 0x1EU}},  // O
}};

void SetLuma(std::vector<std::uint8_t>& frame, VideoMode mode, std::uint32_t x, std::uint32_t y,
             std::uint8_t value) {
    if (x < mode.width && y < mode.height) {
        frame[static_cast<std::size_t>(y) * mode.width + x] = value;
    }
}

void DrawGlyph(std::vector<std::uint8_t>& frame, VideoMode mode, std::uint32_t x, std::uint32_t y,
               const std::array<std::uint8_t, 5>& glyph, std::uint8_t luma) {
    constexpr std::uint32_t scale = 6;
    for (std::uint32_t row = 0; row < glyph.size(); ++row) {
        for (std::uint32_t column = 0; column < 5; ++column) {
            if ((glyph[row] & (1U << (4U - column))) == 0U) {
                continue;
            }
            for (std::uint32_t dy = 0; dy < scale; ++dy) {
                for (std::uint32_t dx = 0; dx < scale; ++dx) {
                    SetLuma(frame, mode, x + column * scale + dx, y + row * scale + dy, luma);
                }
            }
        }
    }
}

void DrawNumber(std::vector<std::uint8_t>& frame, VideoMode mode, std::uint32_t x, std::uint32_t y,
                std::uint64_t value) {
    std::array<std::uint8_t, 20> digits{};
    std::size_t digit_count = 1;
    digits[0] = static_cast<std::uint8_t>(value % 10U);
    while (value >= 10U && digit_count < digits.size()) {
        value /= 10U;
        digits[digit_count++] = static_cast<std::uint8_t>(value % 10U);
    }
    for (std::size_t index = 0; index < digit_count; ++index) {
        DrawGlyph(frame, mode, x + static_cast<std::uint32_t>(index) * 36U, y,
                  kDigits[digits[digit_count - index - 1U]], 235U);
    }
}

}  // namespace

SyntheticFrameProvider::SyntheticFrameProvider(VideoMode mode) : mode_(mode), cadence_(mode.frames_per_second) {
    if (!IsSupportedMode(mode)) {
        throw std::invalid_argument("synthetic provider was given an unsupported mode");
    }
}

Nv12Frame SyntheticFrameProvider::NextFrame(std::uint64_t frame_index) {
    Nv12Frame frame{.mode = mode_, .timestamp_100ns = cadence_.TimestampFor(frame_index),
                    .bytes = std::vector<std::uint8_t>(Nv12FrameSize(mode_))};
    const auto stripe_offset = static_cast<std::uint32_t>((frame_index * 11U) % mode_.width);
    for (std::uint32_t y = 0; y < mode_.height; ++y) {
        for (std::uint32_t x = 0; x < mode_.width; ++x) {
            const auto shifted_x = (x + stripe_offset) % mode_.width;
            const auto band = (shifted_x * 8U) / mode_.width;
            frame.bytes[static_cast<std::size_t>(y) * mode_.width + x] =
                static_cast<std::uint8_t>(32U + band * 24U + ((y / 24U) % 2U) * 12U);
        }
    }

    const auto chroma_offset = static_cast<std::size_t>(mode_.width) * mode_.height;
    for (std::uint32_t y = 0; y < mode_.height / 2U; ++y) {
        for (std::uint32_t x = 0; x < mode_.width; x += 2U) {
            const auto band = ((x + stripe_offset) * 8U) / mode_.width;
            frame.bytes[chroma_offset + static_cast<std::size_t>(y) * mode_.width + x] =
                static_cast<std::uint8_t>(96U + band * 14U);
            frame.bytes[chroma_offset + static_cast<std::size_t>(y) * mode_.width + x + 1U] =
                static_cast<std::uint8_t>(176U - band * 13U);
        }
    }

    constexpr std::array<std::size_t, 10> label_indices{{0, 1, 2, 3, 4, 4, 1, 5, 6, 0}};  // CAMYLLAPOC
    for (std::size_t index = 0; index < label_indices.size(); ++index) {
        DrawGlyph(frame.bytes, mode_, 40U + static_cast<std::uint32_t>(index) * 38U, 40U,
                  kLetters[label_indices[index]], 245U);
    }
    DrawNumber(frame.bytes, mode_, 40U, 96U, frame_index);
    DrawNumber(frame.bytes, mode_, 40U, 144U, mode_.width);
    DrawNumber(frame.bytes, mode_, 226U, 144U, mode_.height);
    DrawNumber(frame.bytes, mode_, 40U, 192U, mode_.frames_per_second);
    return frame;
}

}  // namespace camylla::virtual_camera
