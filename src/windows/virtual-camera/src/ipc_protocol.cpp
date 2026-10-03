#include "camylla/virtual_camera/ipc_protocol.h"

#include <algorithm>

namespace camylla::virtual_camera {
namespace {

std::uint16_t Read16(std::span<const std::uint8_t> data, std::size_t offset) noexcept {
    return static_cast<std::uint16_t>(data[offset] | (data[offset + 1] << 8U));
}
std::uint32_t Read32(std::span<const std::uint8_t> data, std::size_t offset) noexcept {
    return static_cast<std::uint32_t>(data[offset]) |
           (static_cast<std::uint32_t>(data[offset + 1]) << 8U) |
           (static_cast<std::uint32_t>(data[offset + 2]) << 16U) |
           (static_cast<std::uint32_t>(data[offset + 3]) << 24U);
}
std::uint64_t Read64(std::span<const std::uint8_t> data, std::size_t offset) noexcept {
    return static_cast<std::uint64_t>(Read32(data, offset)) |
           (static_cast<std::uint64_t>(Read32(data, offset + 4)) << 32U);
}

}  // namespace

bool ParseIpcHeader(std::span<const std::uint8_t> bytes, std::uint64_t now_ms,
                    IpcFrameHeader& header) noexcept {
    header = {};
    if (bytes.size() != kIpcHeaderBytes || bytes[0] != 'C' || bytes[1] != 'M' ||
        bytes[2] != 'Y' || bytes[3] != 'F' || Read16(bytes, 4) != 1 ||
        std::any_of(bytes.begin() + 64, bytes.end(), [](auto value) { return value != 0; })) return false;
    const auto status = Read16(bytes, 6);
    if (status == 0) return true;
    if (status != 1) return false;
    header.width = Read32(bytes, 8);
    header.height = Read32(bytes, 12);
    header.stride = Read32(bytes, 16);
    header.length = Read32(bytes, 20);
    std::copy_n(bytes.begin() + 24, header.generation.size(), header.generation.begin());
    header.sequence = Read64(bytes, 40);
    header.pts_100ns = static_cast<std::int64_t>(Read64(bytes, 48));
    header.captured_ms = Read64(bytes, 56);
    if (!((header.width == 1280 && header.height == 720) ||
          (header.width == 1920 && header.height == 1080)) ||
        header.stride < header.width || header.stride > 4096 ||
        (header.stride & 1U) != 0 || header.length > kMaxIpcFrameBytes ||
        static_cast<std::uint64_t>(header.stride) * header.height * 3U / 2U != header.length ||
        header.sequence == 0 ||
        std::all_of(header.generation.begin(), header.generation.end(), [](auto part) { return part == 0; }) ||
        header.captured_ms > now_ms + 50 ||
        now_ms > header.captured_ms + kMaxFrameAgeMs) return false;
    header.live = true;
    return true;
}

}  // namespace camylla::virtual_camera
