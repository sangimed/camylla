#include "camylla/virtual_camera/cadence.h"

#include <stdexcept>

namespace camylla::virtual_camera {

FrameCadence::FrameCadence(std::uint32_t frames_per_second) : frames_per_second_(frames_per_second) {
    if (frames_per_second == 0) {
        throw std::invalid_argument("frame rate must not be zero");
    }
}

std::int64_t FrameCadence::TimestampFor(std::uint64_t frame_index) const noexcept {
    return static_cast<std::int64_t>((frame_index * kHundredNanosecondsPerSecond) / frames_per_second_);
}

std::int64_t FrameCadence::DurationFor(std::uint64_t frame_index) const noexcept {
    return TimestampFor(frame_index + 1U) - TimestampFor(frame_index);
}

}  // namespace camylla::virtual_camera
