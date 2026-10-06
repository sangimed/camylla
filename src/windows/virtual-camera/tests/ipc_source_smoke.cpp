#include "camylla/virtual_camera/ipc_frame_provider.h"

#include <Windows.h>
#include <iostream>
#include <algorithm>
#include <string_view>

int main(int argc, char** argv) {
    const bool live = argc == 2 && (std::string_view(argv[1]) == "--live-720p" || std::string_view(argv[1]) == "--live-1080p");
    if (argc > 1 && !live) return 2;
    const camylla::virtual_camera::VideoMode mode = live && std::string_view(argv[1]) == "--live-1080p"
        ? camylla::virtual_camera::VideoMode{1920, 1080, 30} : camylla::virtual_camera::VideoMode{1280, 720, 30};
    camylla::virtual_camera::IpcFrameProvider provider(mode);
    for (int attempt = 0; attempt < 20; ++attempt) {
        const auto frame = provider.NextFrame(static_cast<std::uint64_t>(attempt));
        const auto chroma = static_cast<std::size_t>(mode.width)*mode.height;
        const bool picture = live && std::any_of(frame.bytes.begin()+chroma,frame.bytes.end(),[](std::uint8_t value) { return value != 128; });
        if (!frame.bytes.empty() && (live ? picture : frame.bytes[0] == 37)) {
            std::cout << "Native camera IPC received live NV12 in fixed consumer mode " << mode.width << 'x' << mode.height << " from trusted local producer\n";
            return 0;
        }
        Sleep(100);
    }
    std::cerr << "Native camera IPC did not receive a live frame\n";
    return 1;
}
