#pragma once

#include <cstdint>

namespace camylla::virtual_camera {

inline constexpr std::int64_t kHundredNanosecondsPerSecond = 10'000'000;

class FrameCadence final {
public:
    explicit FrameCadence(std::uint32_t frames_per_second);

    [[nodiscard]] std::int64_t TimestampFor(std::uint64_t frame_index) const noexcept;
    [[nodiscard]] std::int64_t DurationFor(std::uint64_t frame_index) const noexcept;

private:
    std::uint32_t frames_per_second_;
};

}  // namespace camylla::virtual_camera
