#pragma once

#include "camylla/virtual_camera/video_mode.h"

#include <cstdint>
#include <vector>

namespace camylla::virtual_camera {

struct Nv12Frame {
    VideoMode mode;
    std::int64_t timestamp_100ns;
    std::vector<std::uint8_t> bytes;
};

// This is deliberately local-only. A future desktop receiver may implement this
// interface, but the Media Foundation source never owns networking or waits for it.
class FrameProvider {
public:
    virtual ~FrameProvider() = default;
    [[nodiscard]] virtual Nv12Frame NextFrame(std::uint64_t frame_index) = 0;
};

}  // namespace camylla::virtual_camera
