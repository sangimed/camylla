#pragma once

#include "camylla/virtual_camera/cadence.h"
#include "camylla/virtual_camera/frame_provider.h"

namespace camylla::virtual_camera {

class SyntheticFrameProvider final : public FrameProvider {
public:
    explicit SyntheticFrameProvider(VideoMode mode);
    [[nodiscard]] Nv12Frame NextFrame(std::uint64_t frame_index) override;

private:
    VideoMode mode_;
    FrameCadence cadence_;
};

}  // namespace camylla::virtual_camera
