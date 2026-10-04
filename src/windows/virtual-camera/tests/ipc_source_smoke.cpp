#include "camylla/virtual_camera/ipc_frame_provider.h"

#include <Windows.h>
#include <iostream>

int main() {
    camylla::virtual_camera::IpcFrameProvider provider({1280, 720, 30});
    for (int attempt = 0; attempt < 20; ++attempt) {
        const auto frame = provider.NextFrame(static_cast<std::uint64_t>(attempt));
        if (!frame.bytes.empty() && frame.bytes[0] == 37) {
            std::cout << "Native camera IPC received live 720p NV12 from trusted local producer\n";
            return 0;
        }
        Sleep(100);
    }
    std::cerr << "Native camera IPC did not receive a live frame\n";
    return 1;
}
