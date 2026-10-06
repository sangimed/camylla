#pragma once

#include "camylla/virtual_camera/video_mode.h"
#include <span>

namespace camylla::virtual_camera {
// Baseline modes share a 16:9 aspect. Preserve the consumer's negotiated mode
// when the phone changes capture size; resize Y and paired UV independently.
bool ResizeNv12(std::span<const std::uint8_t> input, VideoMode source,
                std::uint32_t stride, std::span<std::uint8_t> output, VideoMode target);
}
