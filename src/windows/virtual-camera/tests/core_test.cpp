#include "camylla/virtual_camera/cadence.h"
#include "camylla/virtual_camera/synthetic_frame_provider.h"
#include "camylla/virtual_camera/video_mode.h"

#include <cstdlib>
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

}  // namespace

int main() {
    ChecksModeAndLayout();
    ChecksCadenceWithoutLongTermDrift();
    ChecksSyntheticFramesAreDeterministicAndComplete();
    return EXIT_SUCCESS;
}
