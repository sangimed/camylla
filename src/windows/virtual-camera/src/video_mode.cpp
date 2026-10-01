#include "camylla/virtual_camera/video_mode.h"

#include <algorithm>
#include <ranges>
#include <stdexcept>

namespace camylla::virtual_camera {

std::span<const VideoMode> SupportedModes() noexcept {
    return kModes;
}

bool IsSupportedMode(VideoMode mode) noexcept {
    return std::ranges::find(SupportedModes(), mode) != SupportedModes().end();
}

std::uint32_t Nv12Stride(VideoMode mode) noexcept {
    return mode.width;
}

std::size_t Nv12FrameSize(VideoMode mode) {
    if (mode.width == 0 || mode.height == 0 || (mode.width % 2) != 0 || (mode.height % 2) != 0) {
        throw std::invalid_argument("NV12 requires a non-zero, even width and height");
    }
    return static_cast<std::size_t>(Nv12Stride(mode)) * mode.height * 3U / 2U;
}

}  // namespace camylla::virtual_camera
