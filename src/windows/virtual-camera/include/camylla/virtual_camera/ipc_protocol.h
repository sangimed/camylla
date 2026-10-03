#pragma once

#include <array>
#include <cstdint>
#include <span>

namespace camylla::virtual_camera {

inline constexpr std::size_t kIpcHeaderBytes = 72;
inline constexpr std::uint64_t kMaxFrameAgeMs = 500;
inline constexpr std::uint32_t kMaxIpcFrameBytes = 4096 * 1080 * 3 / 2;

struct IpcFrameHeader {
    std::uint32_t width{};
    std::uint32_t height{};
    std::uint32_t stride{};
    std::uint32_t length{};
    std::array<std::uint8_t, 16> generation{};
    std::uint64_t sequence{};
    std::int64_t pts_100ns{};
    std::uint64_t captured_ms{};
    bool live{};
};

// Reject malformed, unsupported, expired, or future-dated frames before reading payload.
[[nodiscard]] bool ParseIpcHeader(std::span<const std::uint8_t> bytes, std::uint64_t now_ms,
                                  IpcFrameHeader& header) noexcept;

}  // namespace camylla::virtual_camera
