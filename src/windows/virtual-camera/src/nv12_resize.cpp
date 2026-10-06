#include "camylla/virtual_camera/nv12_resize.h"
#include <algorithm>

namespace camylla::virtual_camera {
bool ResizeNv12(std::span<const std::uint8_t> input, VideoMode source,
                std::uint32_t stride, std::span<std::uint8_t> output, VideoMode target) {
    if (!IsSupportedMode(source) || !IsSupportedMode(target) || stride < source.width || stride > 4096 ||
        input.size() < static_cast<std::size_t>(stride) * source.height * 3 / 2 ||
        output.size() < Nv12FrameSize(target)) return false;
    for (std::uint32_t y = 0; y < target.height; ++y) {
        const auto* row = input.data() + static_cast<std::size_t>(y * source.height / target.height) * stride;
        auto* destination = output.data() + static_cast<std::size_t>(y) * target.width;
        if (source.width == target.width) std::copy_n(row, target.width, destination);
        else for (std::uint32_t x = 0; x < target.width; ++x) destination[x] = row[x * source.width / target.width];
    }
    const auto* chroma = input.data() + static_cast<std::size_t>(stride) * source.height;
    auto* destination_chroma = output.data() + static_cast<std::size_t>(target.width) * target.height;
    for (std::uint32_t y = 0; y < target.height / 2; ++y) {
        const auto* row = chroma + static_cast<std::size_t>(y * source.height / target.height) * stride;
        auto* destination = destination_chroma + static_cast<std::size_t>(y) * target.width;
        for (std::uint32_t x = 0; x < target.width / 2; ++x) {
            const auto sx = x * source.width / target.width;
            destination[x * 2] = row[sx * 2];
            destination[x * 2 + 1] = row[sx * 2 + 1];
        }
    }
    return true;
}
}
