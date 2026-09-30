#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <stdexcept>
#include <vector>

namespace camylla::protocol {

inline constexpr std::array<std::byte, 4> kVideoMagic{
    std::byte{'C'}, std::byte{'M'}, std::byte{'Y'}, std::byte{'V'}};
inline constexpr std::uint8_t kVideoProtocolMajor = 1;
inline constexpr std::uint8_t kVideoProtocolMinor = 0;
inline constexpr std::size_t kVideoHeaderSize = 36;
inline constexpr std::uint32_t kMaxVideoPayloadSize = 4U * 1024U * 1024U;

enum class VideoFrameFlag : std::uint16_t {
    key_frame = 1U << 0U,
    codec_config = 1U << 1U,
};

struct VideoFrame {
    std::uint64_t sequence_number{};
    std::uint32_t configuration_generation{};
    std::uint64_t capture_timestamp_us{};
    std::uint16_t flags{};
    std::vector<std::byte> payload;

    bool operator==(const VideoFrame&) const = default;
};

class ProtocolError final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

[[nodiscard]] std::vector<std::byte> EncodeVideoFrame(const VideoFrame& frame);

// Consumes arbitrary TCP chunks. Once malformed input is observed, the parser
// remains failed so callers cannot accidentally resume on an unauthenticated
// byte boundary; reset the connection and construct a new parser instead.
class VideoFrameParser final {
public:
    [[nodiscard]] std::vector<VideoFrame> Push(std::span<const std::byte> bytes);
    [[nodiscard]] bool failed() const noexcept { return failed_; }
    [[nodiscard]] std::size_t buffered_bytes() const noexcept { return buffer_.size(); }

private:
    [[noreturn]] void Fail(const char* message);
    [[nodiscard]] bool HeaderIsAvailable() const noexcept;
    [[nodiscard]] std::size_t DeclaredFrameSize();
    [[nodiscard]] VideoFrame ConsumeFrame(std::size_t frame_size);

    std::vector<std::byte> buffer_;
    bool failed_{};
};

}  // namespace camylla::protocol
