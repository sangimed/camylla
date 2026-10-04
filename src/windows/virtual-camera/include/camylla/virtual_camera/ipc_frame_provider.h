#pragma once

#include "camylla/virtual_camera/frame_provider.h"
#include "camylla/virtual_camera/ipc_protocol.h"

#include <Windows.h>

#include <array>
#include <vector>

namespace camylla::virtual_camera {

class IpcFrameProvider final : public FrameProvider {
public:
    explicit IpcFrameProvider(VideoMode mode);
    ~IpcFrameProvider() override;
    IpcFrameProvider(const IpcFrameProvider&) = delete;
    IpcFrameProvider& operator=(const IpcFrameProvider&) = delete;
    [[nodiscard]] Nv12Frame NextFrame(std::uint64_t frame_index) override;

private:
    bool Connect();
    bool Transfer(bool writing, std::uint8_t* data, std::size_t length);
    void Disconnect() noexcept;
    void Neutralize(Nv12Frame& frame) const;

    VideoMode mode_;
    HANDLE pipe_{INVALID_HANDLE_VALUE};
    std::vector<std::uint8_t> input_;
    std::array<std::uint8_t, 16> generation_{};
    std::uint64_t last_sequence_{};
    std::uint64_t deadline_ms_{};
};

}  // namespace camylla::virtual_camera
