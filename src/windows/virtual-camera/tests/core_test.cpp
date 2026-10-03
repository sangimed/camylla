#include "camylla/virtual_camera/cadence.h"
#include "camylla/virtual_camera/ipc_protocol.h"
#include "camylla/virtual_camera/synthetic_frame_provider.h"
#include "camylla/virtual_camera/video_mode.h"

#include <cstdlib>
#include <array>
#include <iostream>
#include <stdexcept>
#include <string_view>

namespace {

void Check(bool condition, std::string_view message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << '\n';
        std::exit(EXIT_FAILURE);
    }
}

void ChecksModeAndLayout() {
    using namespace camylla::virtual_camera;
    Check(SupportedModes().size() == 2, "exactly the two intended modes are exposed");
    Check(IsSupportedMode({1280, 720, 30}), "720p30 must be supported");
    Check(IsSupportedMode({1920, 1080, 30}), "1080p30 must be supported");
    Check(!IsSupportedMode({1280, 720, 60}), "60 FPS must not be supported");
    Check(Nv12Stride({1280, 720, 30}) == 1280, "NV12 stride equals width for generated frames");
    Check(Nv12FrameSize({1280, 720, 30}) == 1'382'400, "720p NV12 frame size is correct");
    Check(Nv12FrameSize({1920, 1080, 30}) == 3'110'400, "1080p NV12 frame size is correct");
}

void ChecksCadenceWithoutLongTermDrift() {
    using namespace camylla::virtual_camera;
    FrameCadence cadence(30);
    Check(cadence.TimestampFor(0) == 0, "first frame starts at zero");
    Check(cadence.TimestampFor(30) == kHundredNanosecondsPerSecond,
          "thirty frames equal exactly one second");
    Check(cadence.TimestampFor(900) == 30 * kHundredNanosecondsPerSecond,
          "cadence has no accumulated 30 FPS rounding drift");
    Check(cadence.DurationFor(0) + cadence.DurationFor(1) + cadence.DurationFor(2) > 0,
          "individual frame durations are positive");
}

void ChecksSyntheticFramesAreDeterministicAndComplete() {
    using namespace camylla::virtual_camera;
    SyntheticFrameProvider provider({1280, 720, 30});
    const auto first = provider.NextFrame(7);
    const auto repeat = provider.NextFrame(7);
    const auto next = provider.NextFrame(8);
    Check(first.bytes.size() == Nv12FrameSize(first.mode), "generated frame has complete NV12 layout");
    Check(first.bytes == repeat.bytes, "same frame index produces the same test pattern");
    Check(first.bytes != next.bytes, "moving pattern changes for the next frame");
    Check(first.timestamp_100ns == FrameCadence(30).TimestampFor(7), "timestamp follows source cadence");
}

void Put32(std::array<std::uint8_t, camylla::virtual_camera::kIpcHeaderBytes>& bytes,
           std::size_t offset, std::uint32_t value) {
    for (int i = 0; i < 4; ++i) bytes[offset + i] = static_cast<std::uint8_t>(value >> (8 * i));
}

void Put64(std::array<std::uint8_t, camylla::virtual_camera::kIpcHeaderBytes>& bytes,
           std::size_t offset, std::uint64_t value) {
    Put32(bytes, offset, static_cast<std::uint32_t>(value));
    Put32(bytes, offset + 4, static_cast<std::uint32_t>(value >> 32));
}

void ChecksIpcFrameBoundsAndFreshness() {
    using namespace camylla::virtual_camera;
    std::array<std::uint8_t, kIpcHeaderBytes> bytes{};
    bytes[0] = 'C'; bytes[1] = 'M'; bytes[2] = 'Y'; bytes[3] = 'F';
    bytes[4] = 1; bytes[6] = 1;
    bytes[24] = 1;
    Put64(bytes, 40, 1);
    Put64(bytes, 56, 1000);
    IpcFrameHeader parsed{};
    for (const auto mode : SupportedModes()) {
        Put32(bytes, 8, mode.width); Put32(bytes, 12, mode.height);
        Put32(bytes, 16, mode.width);
        Put32(bytes, 20, static_cast<std::uint32_t>(Nv12FrameSize(mode)));
        Check(ParseIpcHeader(bytes, 1000, parsed) && parsed.live, "supported live frame accepted");
        Check(!ParseIpcHeader(bytes, 1600, parsed), "expired frame rejected");
        Check(!ParseIpcHeader(bytes, 900, parsed), "future timestamp rejected");
        Put32(bytes, 20, kMaxIpcFrameBytes + 1);
        Check(!ParseIpcHeader(bytes, 1000, parsed), "oversized frame rejected");
        Put32(bytes, 16, 4096);
        Put32(bytes, 20, 4096 * mode.height * 3 / 2);
        Check(ParseIpcHeader(bytes, 1000, parsed), "bounded padded NV12 stride accepted");
    }
    Put32(bytes, 8, 1280); Put32(bytes, 12, 720); Put32(bytes, 16, 1280);
    Put32(bytes, 20, 1'382'400);
    bytes[4] = 2;
    Check(!ParseIpcHeader(bytes, 1000, parsed), "unknown version rejected");
    bytes[4] = 1;
    Put32(bytes, 16, 1279);
    Check(!ParseIpcHeader(bytes, 1000, parsed), "invalid stride rejected");
    bytes[6] = 0;
    Check(ParseIpcHeader(bytes, 1000, parsed) && !parsed.live, "neutral response accepted");
    bytes[64] = 1;
    Check(!ParseIpcHeader(bytes, 1000, parsed), "reserved bytes must be zero");
}

}  // namespace

int main() {
    ChecksModeAndLayout();
    ChecksCadenceWithoutLongTermDrift();
    ChecksSyntheticFramesAreDeterministicAndComplete();
    ChecksIpcFrameBoundsAndFreshness();
    return EXIT_SUCCESS;
}
