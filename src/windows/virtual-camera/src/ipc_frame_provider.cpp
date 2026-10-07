#include "camylla/virtual_camera/ipc_frame_provider.h"

#include "camylla/virtual_camera/cadence.h"
#include "camylla/virtual_camera/nv12_resize.h"

#include <algorithm>
#include <stdexcept>
#include <string>
#include <WtsApi32.h>
#include <aclapi.h>

namespace camylla::virtual_camera {
namespace {
constexpr wchar_t kPipePath[] = L"\\\\.\\pipe\\CamyllaFrameV1";
constexpr std::uint64_t kOverallDeadlineMs = 300;

bool IsTrustedDesktopServer(HANDLE pipe) {
    const DWORD console_session = WTSGetActiveConsoleSessionId();
    ULONG server_session = 0;
    if (console_session == 0xFFFFFFFF ||
        !GetNamedPipeServerSessionId(pipe, &server_session) || server_session != console_session) return false;
    LPWSTR user = nullptr;
    LPWSTR domain = nullptr;
    DWORD user_bytes = 0;
    DWORD domain_bytes = 0;
    if (!WTSQuerySessionInformationW(WTS_CURRENT_SERVER_HANDLE, console_session, WTSUserName,
                                     &user, &user_bytes) ||
        !WTSQuerySessionInformationW(WTS_CURRENT_SERVER_HANDLE, console_session, WTSDomainName,
                                     &domain, &domain_bytes)) {
        if (user) WTSFreeMemory(user);
        if (domain) WTSFreeMemory(domain);
        return false;
    }
    const std::wstring account = std::wstring(domain) + L"\\" + user;
    WTSFreeMemory(user);
    WTSFreeMemory(domain);
    DWORD sid_bytes = 0;
    DWORD domain_length = 0;
    SID_NAME_USE kind{};
    LookupAccountNameW(nullptr, account.c_str(), nullptr, &sid_bytes, nullptr, &domain_length, &kind);
    std::vector<std::uint8_t> sid(sid_bytes);
    std::vector<wchar_t> resolved_domain(domain_length);
    if (sid.empty() || !LookupAccountNameW(nullptr, account.c_str(), sid.data(), &sid_bytes,
                                           resolved_domain.data(), &domain_length, &kind)) return false;
    // The service can read the connected pipe's security metadata without obtaining
    // TOKEN_QUERY on an unrelated desktop process. The producer explicitly sets its
    // own user SID as the pipe owner; an unprivileged different user cannot assign it.
    PSID owner = nullptr;
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    const DWORD result = GetSecurityInfo(pipe, SE_KERNEL_OBJECT, OWNER_SECURITY_INFORMATION,
                                         &owner, nullptr, nullptr, nullptr, &descriptor);
    const bool trusted = result == ERROR_SUCCESS && owner != nullptr && EqualSid(owner, sid.data());
    if (descriptor != nullptr) LocalFree(descriptor);
    return trusted;
}
}

IpcFrameProvider::IpcFrameProvider(VideoMode mode) : mode_(mode) {
    if (!IsSupportedMode(mode)) throw std::invalid_argument("unsupported IPC mode");
    input_.resize(kMaxIpcFrameBytes);
}

IpcFrameProvider::~IpcFrameProvider() { Disconnect(); }

void IpcFrameProvider::Disconnect() noexcept {
    if (pipe_ != INVALID_HANDLE_VALUE) CloseHandle(pipe_);
    pipe_ = INVALID_HANDLE_VALUE;
    generation_.fill(0);
    last_sequence_ = 0;
}

bool IpcFrameProvider::Connect() {
    if (pipe_ != INVALID_HANDLE_VALUE) return true;
    if (!WaitNamedPipeW(kPipePath, 20)) return false;
    pipe_ = CreateFileW(kPipePath, GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_EXISTING,
                        FILE_FLAG_OVERLAPPED, nullptr);
    if (pipe_ == INVALID_HANDLE_VALUE) return false;
    if (!IsTrustedDesktopServer(pipe_)) { Disconnect(); return false; }
    return true;
}

bool IpcFrameProvider::Transfer(bool writing, std::uint8_t* data, std::size_t length) {
    std::size_t done = 0;
    while (done < length) {
        const auto now = GetTickCount64();
        if (now >= deadline_ms_) return false;
        HANDLE event = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (event == nullptr) return false;
        OVERLAPPED operation{};
        operation.hEvent = event;
        DWORD transferred = 0;
        const auto remaining = static_cast<DWORD>(std::min<std::size_t>(length - done, 64 * 1024));
        const BOOL started = writing ? WriteFile(pipe_, data + done, remaining, &transferred, &operation)
                                     : ReadFile(pipe_, data + done, remaining, &transferred, &operation);
        bool success = started != 0;
        if (!success && GetLastError() == ERROR_IO_PENDING) {
            success = WaitForSingleObject(event, static_cast<DWORD>(deadline_ms_ - now)) == WAIT_OBJECT_0 &&
                      GetOverlappedResult(pipe_, &operation, &transferred, FALSE) != 0;
            if (!success) {
                CancelIoEx(pipe_, &operation);
                WaitForSingleObject(event, INFINITE);
            }
        }
        CloseHandle(event);
        if (!success || transferred == 0) return false;
        done += transferred;
    }
    return true;
}

void IpcFrameProvider::Neutralize(Nv12Frame& frame) const {
    const auto y_length = static_cast<std::size_t>(mode_.width) * mode_.height;
    std::fill(frame.bytes.begin(), frame.bytes.begin() + y_length, std::uint8_t{16});
    std::fill(frame.bytes.begin() + y_length, frame.bytes.end(), std::uint8_t{128});
}

Nv12Frame IpcFrameProvider::NextFrame(std::uint64_t frame_index) {
    Nv12Frame frame{.mode = mode_, .timestamp_100ns = FrameCadence(mode_.frames_per_second).TimestampFor(frame_index),
                    .bytes = std::vector<std::uint8_t>(Nv12FrameSize(mode_))};
    Neutralize(frame);
    if (!Connect()) return frame;
    deadline_ms_ = GetTickCount64() + kOverallDeadlineMs;
    std::array<std::uint8_t, 4> request{'C', 'M', 'Y', '1'};
    std::array<std::uint8_t, kIpcHeaderBytes> raw_header{};
    if (!Transfer(true, request.data(), request.size()) ||
        !Transfer(false, raw_header.data(), raw_header.size())) {
        Disconnect(); return frame;
    }
    IpcFrameHeader header{};
    if (!ParseIpcHeader(raw_header, GetTickCount64(), header)) {
        Disconnect(); return frame;
    }
    if (!header.live) return frame;
    // Even a mismatched valid frame must be drained before another request.
    if (!Transfer(false, input_.data(), header.length)) {
        Disconnect(); return frame;
    }
    if (GetTickCount64() > header.captured_ms + kMaxFrameAgeMs) return frame;
    if (header.generation == generation_ && header.sequence < last_sequence_) return frame;
    generation_ = header.generation;
    last_sequence_ = header.sequence;
    ResizeNv12(std::span<const std::uint8_t>(input_.data(), header.length),
               {header.width, header.height, 30}, header.stride, frame.bytes, mode_);
    return frame;
}

}  // namespace camylla::virtual_camera
