#pragma once

#include <cstddef>
#include <cstdint>
#include <span>

namespace camylla::virtual_camera {

struct VideoMode {
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t frames_per_second;

    constexpr bool operator==(const VideoMode&) const = default;
};

inline constexpr VideoMode kModes[]{{1280, 720, 30}, {1920, 1080, 30}, {1920, 1080, 60}};

[[nodiscard]] std::span<const VideoMode> SupportedModes() noexcept;
[[nodiscard]] bool IsSupportedMode(VideoMode mode) noexcept;
[[nodiscard]] std::size_t Nv12FrameSize(VideoMode mode);
[[nodiscard]] std::uint32_t Nv12Stride(VideoMode mode) noexcept;

}  // namespace camylla::virtual_camera
