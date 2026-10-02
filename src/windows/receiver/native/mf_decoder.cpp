#include <windows.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfobjects.h>
#include <mftransform.h>
#include <wmcodecdsp.h>
#include <wrl/client.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <vector>

using Microsoft::WRL::ComPtr;

namespace {
using FrameCallback = void(__cdecl*)(const std::uint8_t*, std::uint32_t, std::uint32_t,
                                     std::uint32_t, std::uint32_t, std::int64_t, void*);

struct Decoder {
    ComPtr<IMFTransform> transform;
    std::uint32_t width{};
    std::uint32_t height{};
    std::uint32_t stride{};
    FrameCallback callback{};
    void* context{};
};

HRESULT ConfigureOutput(Decoder& decoder) {
    for (DWORD index = 0;; ++index) {
        ComPtr<IMFMediaType> type;
        const HRESULT available = decoder.transform->GetOutputAvailableType(0, index, &type);
        if (FAILED(available)) return available;
        GUID subtype{};
        if (SUCCEEDED(type->GetGUID(MF_MT_SUBTYPE, &subtype)) && subtype == MFVideoFormat_NV12) {
            const HRESULT selected = decoder.transform->SetOutputType(0, type.Get(), 0);
            if (SUCCEEDED(selected) && FAILED(type->GetUINT32(MF_MT_DEFAULT_STRIDE, &decoder.stride)))
                decoder.stride = decoder.width;
            return selected;
        }
    }
}

HRESULT CreateDecoder(Decoder& decoder) {
    HRESULT result = CoCreateInstance(CLSID_CMSH264DecoderMFT, nullptr, CLSCTX_INPROC_SERVER,
                                      IID_PPV_ARGS(&decoder.transform));
    if (FAILED(result)) return result;
    ComPtr<IMFAttributes> attributes;
    result = decoder.transform->GetAttributes(&attributes);
    if (FAILED(result)) return result;
    result = attributes->SetUINT32(MF_LOW_LATENCY, 1);
    if (FAILED(result)) return result;
    ComPtr<IMFMediaType> input;
    result = MFCreateMediaType(&input);
    if (FAILED(result)) return result;
    result = input->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    if (FAILED(result)) return result;
    result = input->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
    if (FAILED(result)) return result;
    result = MFSetAttributeSize(input.Get(), MF_MT_FRAME_SIZE, decoder.width, decoder.height);
    if (FAILED(result)) return result;
    result = MFSetAttributeRatio(input.Get(), MF_MT_FRAME_RATE, 30, 1);
    if (FAILED(result)) return result;
    result = decoder.transform->SetInputType(0, input.Get(), 0);
    if (FAILED(result)) return result;
    result = ConfigureOutput(decoder);
    if (FAILED(result)) return result;
    result = decoder.transform->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    if (FAILED(result)) return result;
    return decoder.transform->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
}

HRESULT Drain(Decoder& decoder, std::uint32_t& count) {
    for (;;) {
        MFT_OUTPUT_STREAM_INFO info{};
        HRESULT result = decoder.transform->GetOutputStreamInfo(0, &info);
        if (FAILED(result)) return result;
        ComPtr<IMFSample> sample;
        if ((info.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) == 0) {
            result = MFCreateSample(&sample);
            if (FAILED(result)) return result;
            ComPtr<IMFMediaBuffer> buffer;
            const DWORD capacity = std::max<DWORD>(info.cbSize, decoder.width * decoder.height * 3U / 2U);
            result = MFCreateMemoryBuffer(capacity, &buffer);
            if (FAILED(result)) return result;
            result = sample->AddBuffer(buffer.Get());
            if (FAILED(result)) return result;
        }
        MFT_OUTPUT_DATA_BUFFER output{};
        output.dwStreamID = 0;
        output.pSample = sample.Get();
        DWORD status{};
        result = decoder.transform->ProcessOutput(0, 1, &output, &status);
        if (result == MF_E_TRANSFORM_NEED_MORE_INPUT) return S_OK;
        if (result == MF_E_TRANSFORM_STREAM_CHANGE) {
            result = ConfigureOutput(decoder);
            if (FAILED(result)) return result;
            continue;
        }
        if (FAILED(result)) return result;
        IMFSample* delivered = output.pSample ? output.pSample : sample.Get();
        if (delivered == nullptr) return E_FAIL;
        ComPtr<IMFMediaBuffer> decoded;
        result = delivered->ConvertToContiguousBuffer(&decoded);
        if (FAILED(result)) {
            if (output.pSample && output.pSample != sample.Get()) output.pSample->Release();
            return result;
        }
        BYTE* bytes{};
        DWORD length{};
        result = decoded->Lock(&bytes, nullptr, &length);
        if (SUCCEEDED(result)) {
            LONGLONG timestamp{};
            delivered->GetSampleTime(&timestamp);
            // The pointer belongs to this synchronous callback only. The receiver must copy it
            // before returning if preview or virtual-camera publication needs to retain it.
            decoder.callback(bytes, length, decoder.width, decoder.height, decoder.stride,
                             timestamp, decoder.context);
            decoded->Unlock();
            ++count;
        }
        if (output.pSample && output.pSample != sample.Get()) output.pSample->Release();
        if (FAILED(result)) return result;
    }
}
}  // namespace

extern "C" __declspec(dllexport) HRESULT __cdecl CamyllaDecoderCreate(
    std::uint32_t width, std::uint32_t height, FrameCallback callback, void* context, void** handle) {
    if (!handle || !callback || width == 0 || height == 0) return E_INVALIDARG;
    *handle = nullptr;
    HRESULT result = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(result)) return result;
    result = MFStartup(MF_VERSION);
    if (FAILED(result)) { CoUninitialize(); return result; }
    auto decoder = std::make_unique<Decoder>();
    decoder->width = width;
    decoder->height = height;
    decoder->callback = callback;
    decoder->context = context;
    result = CreateDecoder(*decoder);
    if (FAILED(result)) { decoder.reset(); MFShutdown(); CoUninitialize(); return result; }
    *handle = decoder.release();
    CoUninitialize();
    return S_OK;
}

extern "C" __declspec(dllexport) HRESULT __cdecl CamyllaDecoderPush(
    void* handle, const std::uint8_t* bytes, std::uint32_t length, std::uint64_t pts_us,
    std::uint32_t* decoded_frames) {
    if (!handle || !bytes || !length || !decoded_frames) return E_INVALIDARG;
    HRESULT initialization = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(initialization)) return initialization;
    auto& decoder = *static_cast<Decoder*>(handle);
    *decoded_frames = 0;
    ComPtr<IMFSample> sample;
    HRESULT result = MFCreateSample(&sample);
    if (FAILED(result)) { CoUninitialize(); return result; }
    ComPtr<IMFMediaBuffer> buffer;
    result = MFCreateMemoryBuffer(length, &buffer);
    if (FAILED(result)) { CoUninitialize(); return result; }
    BYTE* destination{};
    result = buffer->Lock(&destination, nullptr, nullptr);
    if (FAILED(result)) { CoUninitialize(); return result; }
    memcpy(destination, bytes, length);
    buffer->Unlock();
    result = buffer->SetCurrentLength(length);
    if (FAILED(result)) { CoUninitialize(); return result; }
    result = sample->AddBuffer(buffer.Get());
    if (FAILED(result)) { CoUninitialize(); return result; }
    result = sample->SetSampleTime(static_cast<LONGLONG>(pts_us) * 10);
    if (FAILED(result)) { CoUninitialize(); return result; }
    result = sample->SetSampleDuration(333333);
    if (FAILED(result)) { CoUninitialize(); return result; }
    result = decoder.transform->ProcessInput(0, sample.Get(), 0);
    if (result == MF_E_NOTACCEPTING) {
        result = Drain(decoder, *decoded_frames);
        if (FAILED(result)) { CoUninitialize(); return result; }
        result = decoder.transform->ProcessInput(0, sample.Get(), 0);
    }
    if (FAILED(result)) { CoUninitialize(); return result; }
    result = Drain(decoder, *decoded_frames);
    CoUninitialize();
    return result;
}

extern "C" __declspec(dllexport) HRESULT __cdecl CamyllaDecoderReset(void* handle) {
    if (!handle) return E_INVALIDARG;
    HRESULT initialization = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(initialization)) return initialization;
    auto& decoder = *static_cast<Decoder*>(handle);
    HRESULT result = decoder.transform->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
    if (SUCCEEDED(result)) result = decoder.transform->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    CoUninitialize();
    return result;
}

extern "C" __declspec(dllexport) void __cdecl CamyllaDecoderDestroy(void* handle) {
    if (!handle) return;
    const HRESULT initialization = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    delete static_cast<Decoder*>(handle);
    MFShutdown();
    if (SUCCEEDED(initialization)) CoUninitialize();
}
