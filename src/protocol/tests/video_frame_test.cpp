#include "camylla/protocol/video_frame.h"

#include <cstdlib>
#include <iostream>
#include <span>
#include <string_view>

using camylla::protocol::EncodeVideoFrame;
using camylla::protocol::ProtocolError;
using camylla::protocol::VideoFrame;
using camylla::protocol::VideoFrameFlag;
using camylla::protocol::VideoFrameParser;

namespace {

void Check(bool condition, std::string_view message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << '\n';
        std::exit(EXIT_FAILURE);
    }
}

template <typename Action>
void CheckProtocolError(Action action, std::string_view message) {
    try {
        action();
    } catch (const ProtocolError&) {
        return;
    }
    Check(false, message);
}

VideoFrame ExampleFrame(std::uint64_t sequence) {
    return VideoFrame{
        .sequence_number = sequence,
        .configuration_generation = 7,
        .capture_timestamp_us = 9'876'543,
        .send_timestamp_us = 9'876'600,
        .session_id = {std::byte{0x42}},
        .flags = static_cast<std::uint16_t>(VideoFrameFlag::key_frame),
        .payload = {std::byte{0x00}, std::byte{0x00}, std::byte{0x01}, std::byte{0x65}},
    };
}

void RoundTripsFragmentedInput() {
    const auto expected = ExampleFrame(42);
    const auto encoded = EncodeVideoFrame(expected);
    VideoFrameParser parser;
    for (std::size_t index = 0; index < encoded.size(); ++index) {
        const auto frames = parser.Push(std::span(encoded).subspan(index, 1));
        if (index + 1 < encoded.size()) {
            Check(frames.empty(), "a partial frame must not be emitted");
        } else {
            Check(frames.size() == 1 && frames.front() == expected,
                  "a complete fragmented frame must round trip");
        }
    }
    Check(parser.buffered_bytes() == 0, "a complete frame must leave no buffered bytes");
}

void ParsesSeveralFramesFromOneChunk() {
    const auto first = ExampleFrame(1);
    const auto second = ExampleFrame(2);
    auto bytes = EncodeVideoFrame(first);
    const auto second_bytes = EncodeVideoFrame(second);
    bytes.insert(bytes.end(), second_bytes.begin(), second_bytes.end());

    VideoFrameParser parser;
    const auto frames = parser.Push(bytes);
    Check(frames.size() == 2 && frames[0] == first && frames[1] == second,
          "coalesced TCP frames must parse independently");
}

void RejectsMalformedInputAndStaysFailed() {
    auto bytes = EncodeVideoFrame(ExampleFrame(1));
    bytes[0] = std::byte{'X'};
    VideoFrameParser parser;
    CheckProtocolError([&] { static_cast<void>(parser.Push(bytes)); },
                       "invalid magic must be rejected");
    Check(parser.failed(), "malformed input must poison the parser");
    CheckProtocolError([&] { static_cast<void>(parser.Push({})); },
                       "a poisoned parser must not resume");
}

void RejectsOversizedDeclarationBeforePayload() {
    auto bytes = EncodeVideoFrame(ExampleFrame(1));
    bytes[8] = std::byte{0x00};
    bytes[9] = std::byte{0x40};
    bytes[10] = std::byte{0x00};
    bytes[11] = std::byte{0x01};
    bytes.resize(camylla::protocol::kVideoHeaderSize);

    VideoFrameParser parser;
    CheckProtocolError([&] { static_cast<void>(parser.Push(bytes)); },
                       "oversized declarations must be rejected from the header alone");
    Check(parser.buffered_bytes() == 0, "rejected data must be discarded");
}

void RejectsInvalidOutgoingFrames() {
    auto frame = ExampleFrame(1);
    frame.payload.clear();
    CheckProtocolError([&] { static_cast<void>(EncodeVideoFrame(frame)); },
                       "empty outgoing payloads must be rejected");
    frame = ExampleFrame(1);
    frame.flags = 0x8000;
    CheckProtocolError([&] { static_cast<void>(EncodeVideoFrame(frame)); },
                       "unknown outgoing flags must be rejected");
    frame = ExampleFrame(1);
    frame.flags = 0;
    CheckProtocolError([&] { static_cast<void>(EncodeVideoFrame(frame)); },
                       "frame kind is mandatory");
    frame = ExampleFrame(1);
    frame.message_type = 9;
    CheckProtocolError([&] { static_cast<void>(EncodeVideoFrame(frame)); },
                       "unknown message type must be rejected");
}

void RejectsVersionTypeAndTruncation() {
    const auto example = EncodeVideoFrame(ExampleFrame(9));
    auto wrong_version = example;
    wrong_version[4] = std::byte{99};
    VideoFrameParser version_parser;
    CheckProtocolError([&] { static_cast<void>(version_parser.Push(wrong_version)); },
                       "unsupported version must fail");
    auto wrong_type = example;
    wrong_type[35] = std::byte{9};
    VideoFrameParser type_parser;
    CheckProtocolError([&] { static_cast<void>(type_parser.Push(wrong_type)); },
                       "unknown type must fail");
    VideoFrameParser truncated_parser;
    const auto frames = truncated_parser.Push(std::span(example).first(example.size() - 1));
    Check(frames.empty() && truncated_parser.buffered_bytes() == example.size() - 1,
          "truncated access unit must remain incomplete");
}

}  // namespace

int main() {
    RoundTripsFragmentedInput();
    ParsesSeveralFramesFromOneChunk();
    RejectsMalformedInputAndStaysFailed();
    RejectsOversizedDeclarationBeforePayload();
    RejectsInvalidOutgoingFrames();
    RejectsVersionTypeAndTruncation();
    return EXIT_SUCCESS;
}
