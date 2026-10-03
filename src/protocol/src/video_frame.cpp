#include "camylla/protocol/video_frame.h"

#include <algorithm>
#include <limits>
#include <string>
#include <type_traits>

namespace camylla::protocol {
namespace {

constexpr std::uint16_t kKnownFlags =
    static_cast<std::uint16_t>(VideoFrameFlag::key_frame) |
    static_cast<std::uint16_t>(VideoFrameFlag::codec_config) |
    static_cast<std::uint16_t>(VideoFrameFlag::normal_frame);

template <typename T>
void AppendBigEndian(std::vector<std::byte>& output, T value) {
    static_assert(std::is_unsigned_v<T>);
    for (std::size_t shift = sizeof(T); shift > 0; --shift) {
        output.push_back(static_cast<std::byte>(value >> ((shift - 1U) * 8U)));
    }
}

template <typename T>
T ReadBigEndian(std::span<const std::byte> input, std::size_t offset) {
    static_assert(std::is_unsigned_v<T>);
    T value{};
    for (std::size_t index = 0; index < sizeof(T); ++index) {
        value = static_cast<T>((value << 8U) |
                               std::to_integer<unsigned int>(input[offset + index]));
    }
    return value;
}

void ValidateFrame(const VideoFrame& frame) {
    if (frame.payload.empty()) {
        throw ProtocolError("video payload must not be empty");
    }
    if (frame.payload.size() > kMaxVideoPayloadSize) {
        throw ProtocolError("video payload exceeds the configured limit");
    }
    if ((frame.flags & ~kKnownFlags) != 0U) {
        throw ProtocolError("video frame contains unknown flags");
    }
    if (frame.flags == 0U || (frame.flags & (frame.flags - 1U)) != 0U) {
        throw ProtocolError("video frame must have exactly one frame kind");
    }
    if (frame.message_type != 1U) {
        throw ProtocolError("unsupported video message type");
    }
}

}  // namespace

std::vector<std::byte> EncodeVideoFrame(const VideoFrame& frame) {
    ValidateFrame(frame);

    std::vector<std::byte> output;
    output.reserve(kVideoHeaderSize + frame.payload.size());
    output.insert(output.end(), kVideoMagic.begin(), kVideoMagic.end());
    AppendBigEndian(output, kVideoProtocolMajor);
    AppendBigEndian(output, kVideoProtocolMinor);
    AppendBigEndian(output, static_cast<std::uint16_t>(kVideoHeaderSize));
    AppendBigEndian(output, static_cast<std::uint32_t>(frame.payload.size()));
    AppendBigEndian(output, frame.sequence_number);
    AppendBigEndian(output, frame.configuration_generation);
    AppendBigEndian(output, frame.capture_timestamp_us);
    AppendBigEndian(output, frame.flags);
    AppendBigEndian(output, frame.message_type);
    output.insert(output.end(), frame.session_id.begin(), frame.session_id.end());
    AppendBigEndian(output, frame.send_timestamp_us);
    AppendBigEndian(output, std::uint32_t{0});
    output.insert(output.end(), frame.payload.begin(), frame.payload.end());
    return output;
}

std::vector<VideoFrame> VideoFrameParser::Push(std::span<const std::byte> bytes) {
    if (failed_) {
        throw ProtocolError("video parser is in a failed state");
    }

    std::vector<VideoFrame> frames;
    std::size_t input_offset = 0;
    while (input_offset < bytes.size()) {
        const auto capacity = kVideoHeaderSize + kMaxVideoPayloadSize - buffer_.size();
        if (capacity == 0U) {
            Fail("video frame exceeds the configured buffer limit");
        }
        const auto amount = std::min(capacity, bytes.size() - input_offset);
        buffer_.insert(buffer_.end(), bytes.begin() + static_cast<std::ptrdiff_t>(input_offset),
                       bytes.begin() + static_cast<std::ptrdiff_t>(input_offset + amount));
        input_offset += amount;

        while (HeaderIsAvailable()) {
            const auto frame_size = DeclaredFrameSize();
            if (buffer_.size() < frame_size) {
                break;
            }
            frames.push_back(ConsumeFrame(frame_size));
        }
    }
    return frames;
}

[[noreturn]] void VideoFrameParser::Fail(const char* message) {
    failed_ = true;
    buffer_.clear();
    throw ProtocolError(message);
}

bool VideoFrameParser::HeaderIsAvailable() const noexcept {
    return buffer_.size() >= kVideoHeaderSize;
}

std::size_t VideoFrameParser::DeclaredFrameSize() {
    const auto input = std::span<const std::byte>(buffer_);
    if (!std::equal(kVideoMagic.begin(), kVideoMagic.end(), input.begin())) {
        Fail("invalid video frame magic");
    }
    if (ReadBigEndian<std::uint8_t>(input, 4) != kVideoProtocolMajor) {
        Fail("unsupported video protocol major version");
    }
    if (ReadBigEndian<std::uint8_t>(input, 5) > kVideoProtocolMinor) {
        Fail("unsupported video protocol minor version");
    }
    if (ReadBigEndian<std::uint16_t>(input, 6) != kVideoHeaderSize) {
        Fail("unsupported video header size");
    }
    const auto payload_size = ReadBigEndian<std::uint32_t>(input, 8);
    if (payload_size == 0U || payload_size > kMaxVideoPayloadSize) {
        Fail("invalid video payload size");
    }
    if ((ReadBigEndian<std::uint16_t>(input, 32) & ~kKnownFlags) != 0U) {
        Fail("video frame contains unknown flags");
    }
    const auto flags = ReadBigEndian<std::uint16_t>(input, 32);
    if (flags == 0U || (flags & (flags - 1U)) != 0U) {
        Fail("video frame must have exactly one frame kind");
    }
    if (ReadBigEndian<std::uint16_t>(input, 34) != 1U) {
        Fail("unsupported video message type");
    }
    if (ReadBigEndian<std::uint32_t>(input, 60) != 0U) {
        Fail("video frame reserved field is not zero");
    }
    return kVideoHeaderSize + payload_size;
}

VideoFrame VideoFrameParser::ConsumeFrame(std::size_t frame_size) {
    const auto input = std::span<const std::byte>(buffer_);
    VideoFrame frame{
        .sequence_number = ReadBigEndian<std::uint64_t>(input, 12),
        .configuration_generation = ReadBigEndian<std::uint32_t>(input, 20),
        .capture_timestamp_us = ReadBigEndian<std::uint64_t>(input, 24),
        .send_timestamp_us = ReadBigEndian<std::uint64_t>(input, 52),
        .session_id = {},
        .message_type = ReadBigEndian<std::uint16_t>(input, 34),
        .flags = ReadBigEndian<std::uint16_t>(input, 32),
        .payload = std::vector<std::byte>(input.begin() + kVideoHeaderSize,
                                         input.begin() + static_cast<std::ptrdiff_t>(frame_size)),
    };
    std::copy_n(input.begin() + 36, frame.session_id.size(), frame.session_id.begin());
    buffer_.erase(buffer_.begin(), buffer_.begin() + static_cast<std::ptrdiff_t>(frame_size));
    return frame;
}

}  // namespace camylla::protocol
