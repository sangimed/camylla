#include "media_source.h"

#include "camylla/virtual_camera/cadence.h"
#include "camylla/virtual_camera/ipc_frame_provider.h"
#include "camylla/virtual_camera/synthetic_frame_provider.h"
#include "camylla/virtual_camera/video_mode.h"

#include <mfapi.h>
#include <mferror.h>
#include <mfobjects.h>
#include <ks.h>
#include <ksmedia.h>
#include <ksproxy.h>
#include <wrl/client.h>
#include <sddl.h>

#include <chrono>
#include <algorithm>
#include <memory>
#include <mutex>
#include <thread>
#include <atomic>
#include <cwchar>
#include <vector>

using Microsoft::WRL::ComPtr;
using Microsoft::WRL::RuntimeClass;
using Microsoft::WRL::RuntimeClassFlags;
using Microsoft::WRL::ClassicCom;
using Microsoft::WRL::ChainInterfaces;

namespace camylla::virtual_camera::win {

const CLSID kSourceClsid = {0xca9f0b23, 0xc7e0, 0x42c7, {0x94, 0xb4, 0x2d, 0x6a, 0x5f, 0xad, 0x54, 0x4d}};

namespace {

void ReportSourceToken() {
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return;
    DWORD size = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &size);
    std::vector<std::uint8_t> data(size);
    if (size != 0 && GetTokenInformation(token, TokenUser, data.data(), size, &size)) {
        const auto* user = reinterpret_cast<const TOKEN_USER*>(data.data());
        LPWSTR sid = nullptr;
        if (ConvertSidToStringSidW(user->User.Sid, &sid)) {
            wchar_t message[256]{};
            swprintf_s(message, L"Camylla camera: source pid=%lu token user SID=%ls\n",
                       GetCurrentProcessId(), sid);
            OutputDebugStringW(message);
            LocalFree(sid);
        }
    }
    DWORD sid_bytes = 0;
    DWORD domain_bytes = 0;
    SID_NAME_USE use{};
    LookupAccountNameW(nullptr, L"NT SERVICE\\FrameServer", nullptr, &sid_bytes,
                       nullptr, &domain_bytes, &use);
    std::vector<std::uint8_t> service_sid(sid_bytes);
    std::vector<wchar_t> domain(domain_bytes);
    if (sid_bytes != 0 && LookupAccountNameW(nullptr, L"NT SERVICE\\FrameServer",
        service_sid.data(), &sid_bytes, domain.data(), &domain_bytes, &use)) {
        DWORD groups_bytes = 0;
        GetTokenInformation(token, TokenGroups, nullptr, 0, &groups_bytes);
        std::vector<std::uint8_t> groups_data(groups_bytes);
        bool present = false;
        if (groups_bytes != 0 &&
            GetTokenInformation(token, TokenGroups, groups_data.data(), groups_bytes, &groups_bytes)) {
            const auto* groups = reinterpret_cast<const TOKEN_GROUPS*>(groups_data.data());
            for (DWORD index = 0; index < groups->GroupCount; ++index)
                if (EqualSid(groups->Groups[index].Sid, service_sid.data())) present = true;
        }
        OutputDebugStringW(present ? L"Camylla camera: FrameServer service SID present\n"
                                   : L"Camylla camera: FrameServer service SID absent\n");
    }
    CloseHandle(token);
}

enum class SourceState { stopped, started, shutdown };

HRESULT LogSampleFailure(const wchar_t* step, HRESULT hr) {
    wchar_t message[192]{};
    swprintf_s(message, L"Camylla camera: RequestSample %s failed pid=%lu hr=0x%08lX\n",
               step, GetCurrentProcessId(), static_cast<unsigned long>(hr));
    OutputDebugStringW(message);
    return hr;
}

class CamyllaMediaSource;

HRESULT SetFrameServerStreamAttributes(IMFAttributes* attributes, DWORD stream_id) {
    if (attributes == nullptr) return E_POINTER;
    HRESULT hr = attributes->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY, PINNAME_VIDEO_CAPTURE);
    if (FAILED(hr)) return hr;
    if (FAILED(hr = attributes->SetUINT32(MF_DEVICESTREAM_STREAM_ID, stream_id))) return hr;
    if (FAILED(hr = attributes->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED, 1))) return hr;
    return attributes->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES,
                                 MFFrameSourceTypes_Color);
}

HRESULT CreateMediaType(VideoMode mode, IMFMediaType** media_type) {
    if (media_type == nullptr) {
        return E_POINTER;
    }
    *media_type = nullptr;
    ComPtr<IMFMediaType> result;
    HRESULT hr = MFCreateMediaType(&result);
    if (FAILED(hr)) return hr;
    if (FAILED(hr = result->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video)) ||
        FAILED(hr = result->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12)) ||
        FAILED(hr = MFSetAttributeSize(result.Get(), MF_MT_FRAME_SIZE, mode.width, mode.height)) ||
        FAILED(hr = MFSetAttributeRatio(result.Get(), MF_MT_FRAME_RATE, mode.frames_per_second, 1)) ||
        FAILED(hr = MFSetAttributeRatio(result.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1)) ||
        FAILED(hr = result->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive)) ||
        FAILED(hr = result->SetUINT32(MF_MT_FIXED_SIZE_SAMPLES, TRUE)) ||
        FAILED(hr = result->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE)) ||
        FAILED(hr = result->SetUINT32(MF_MT_SAMPLE_SIZE, static_cast<UINT32>(Nv12FrameSize(mode)))) ||
        FAILED(hr = result->SetUINT32(MF_MT_DEFAULT_STRIDE, Nv12Stride(mode)))) {
        return hr;
    }
    *media_type = result.Detach();
    return S_OK;
}

class CamyllaMediaStream final
    : public RuntimeClass<RuntimeClassFlags<ClassicCom>,
                          ChainInterfaces<IMFMediaStream2, IMFMediaStream, IMFMediaEventGenerator>> {
public:
    HRESULT Initialize(CamyllaMediaSource* parent, DWORD stream_id, IMFStreamDescriptor* descriptor);
    HRESULT Start(IMFMediaType* type);
    HRESULT Stop(bool queue_event);
    HRESULT Shutdown();
    HRESULT GetAttributes(IMFAttributes** attributes);
    HRESULT SetSampleAllocator(IMFVideoSampleAllocator* allocator);

    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) override;
    STDMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override;
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override;
    STDMETHODIMP QueueEvent(MediaEventType type, REFGUID extended_type, HRESULT status,
                            const PROPVARIANT* event_value) override;
    STDMETHODIMP GetMediaSource(IMFMediaSource** source) override;
    STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** descriptor) override;
    STDMETHODIMP RequestSample(IUnknown* token) override;
    STDMETHODIMP SetStreamState(MF_STREAM_STATE state) override;
    STDMETHODIMP GetStreamState(MF_STREAM_STATE* state) override;

private:
    std::mutex mutex_;
    ComPtr<IMFMediaSource> parent_;
    ComPtr<IMFMediaEventQueue> events_;
    ComPtr<IMFStreamDescriptor> descriptor_;
    ComPtr<IMFAttributes> attributes_;
    ComPtr<IMFVideoSampleAllocator> allocator_;
    std::atomic<std::uint64_t> requests_{0};
    std::atomic<std::uint64_t> delivered_{0};
    std::shared_ptr<FrameProvider> provider_;
    VideoMode mode_{};
    std::uint64_t frame_index_{};
    bool started_{};
    bool shutdown_{};
    MF_STREAM_STATE stream_state_{MF_STREAM_STATE_STOPPED};
    std::chrono::steady_clock::time_point next_deadline_{};
};

class CamyllaMediaSource final
    : public RuntimeClass<RuntimeClassFlags<ClassicCom>,
                          ChainInterfaces<IMFMediaSourceEx, IMFMediaSource, IMFMediaEventGenerator>, IMFGetService,
                          IKsControl, IMFSampleAllocatorControl> {
public:
    HRESULT Initialize(IMFAttributes* activation_attributes);

    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) override;
    STDMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override;
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override;
    STDMETHODIMP QueueEvent(MediaEventType type, REFGUID extended_type, HRESULT status,
                            const PROPVARIANT* event_value) override;
    STDMETHODIMP GetCharacteristics(DWORD* characteristics) override;
    STDMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor** descriptor) override;
    STDMETHODIMP Start(IMFPresentationDescriptor* descriptor, const GUID* time_format,
                       const PROPVARIANT* start_position) override;
    STDMETHODIMP Stop() override;
    STDMETHODIMP Pause() override;
    STDMETHODIMP Shutdown() override;
    STDMETHODIMP GetSourceAttributes(IMFAttributes** attributes) override;
    STDMETHODIMP GetStreamAttributes(DWORD stream_identifier, IMFAttributes** attributes) override;
    STDMETHODIMP SetD3DManager(IUnknown* manager) override;
    STDMETHODIMP GetService(REFGUID service, REFIID iid, LPVOID* object) override;
    STDMETHODIMP KsProperty(PKSPROPERTY property, ULONG property_length, LPVOID property_data,
                            ULONG data_length, ULONG* bytes_returned) override;
    STDMETHODIMP KsMethod(PKSMETHOD method, ULONG method_length, LPVOID method_data,
                          ULONG data_length, ULONG* bytes_returned) override;
    STDMETHODIMP KsEvent(PKSEVENT event, ULONG event_length, LPVOID event_data,
                         ULONG data_length, ULONG* bytes_returned) override;
    STDMETHODIMP SetDefaultAllocator(DWORD stream_id, IUnknown* allocator) override;
    STDMETHODIMP GetAllocatorUsage(DWORD stream_id, DWORD* input_stream_id,
                                   MFSampleAllocatorUsage* usage) override;

private:
    std::mutex mutex_;
    SourceState state_{SourceState::stopped};
    ComPtr<IMFMediaEventQueue> events_;
    ComPtr<IMFPresentationDescriptor> presentation_descriptor_;
    ComPtr<IMFAttributes> attributes_;
    ComPtr<CamyllaMediaStream> stream_;
};

class CamyllaActivate final : public RuntimeClass<RuntimeClassFlags<ClassicCom>,
                                                  ChainInterfaces<IMFActivate, IMFAttributes>> {
public:
    HRESULT Initialize() { return MFCreateAttributes(&attributes_, 0); }

    STDMETHODIMP ActivateObject(REFIID iid, void** object) override {
        if (object == nullptr) return E_POINTER;
        *object = nullptr;
        ComPtr<CamyllaMediaSource> source = Microsoft::WRL::Make<CamyllaMediaSource>();
        if (!source) return E_OUTOFMEMORY;
        HRESULT hr = source->Initialize(attributes_.Get());
        return SUCCEEDED(hr) ? source.CopyTo(iid, object) : hr;
    }
    STDMETHODIMP ShutdownObject() override { return E_NOTIMPL; }
    STDMETHODIMP DetachObject() override { return E_NOTIMPL; }

#define CAMYLLA_FORWARD_ATTRIBUTE(method, ...) return attributes_->method(__VA_ARGS__)
    STDMETHODIMP GetItem(REFGUID key, PROPVARIANT* item_value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetItem, key, item_value); }
    STDMETHODIMP GetItemType(REFGUID key, MF_ATTRIBUTE_TYPE* type) override { CAMYLLA_FORWARD_ATTRIBUTE(GetItemType, key, type); }
    STDMETHODIMP CompareItem(REFGUID key, REFPROPVARIANT item_value, BOOL* result) override { CAMYLLA_FORWARD_ATTRIBUTE(CompareItem, key, item_value, result); }
    STDMETHODIMP Compare(IMFAttributes* theirs, MF_ATTRIBUTES_MATCH_TYPE type, BOOL* result) override { CAMYLLA_FORWARD_ATTRIBUTE(Compare, theirs, type, result); }
    STDMETHODIMP GetUINT32(REFGUID key, UINT32* out_value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetUINT32, key, out_value); }
    STDMETHODIMP GetUINT64(REFGUID key, UINT64* out_value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetUINT64, key, out_value); }
    STDMETHODIMP GetDouble(REFGUID key, double* out_value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetDouble, key, out_value); }
    STDMETHODIMP GetGUID(REFGUID key, GUID* out_value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetGUID, key, out_value); }
    STDMETHODIMP GetStringLength(REFGUID key, UINT32* length) override { CAMYLLA_FORWARD_ATTRIBUTE(GetStringLength, key, length); }
    STDMETHODIMP GetString(REFGUID key, LPWSTR out_value, UINT32 size, UINT32* length) override { CAMYLLA_FORWARD_ATTRIBUTE(GetString, key, out_value, size, length); }
    STDMETHODIMP GetAllocatedString(REFGUID key, LPWSTR* out_value, UINT32* length) override { CAMYLLA_FORWARD_ATTRIBUTE(GetAllocatedString, key, out_value, length); }
    STDMETHODIMP GetBlobSize(REFGUID key, UINT32* size) override { CAMYLLA_FORWARD_ATTRIBUTE(GetBlobSize, key, size); }
    STDMETHODIMP GetBlob(REFGUID key, UINT8* out_value, UINT32 size, UINT32* written) override { CAMYLLA_FORWARD_ATTRIBUTE(GetBlob, key, out_value, size, written); }
    STDMETHODIMP GetAllocatedBlob(REFGUID key, UINT8** out_value, UINT32* size) override { CAMYLLA_FORWARD_ATTRIBUTE(GetAllocatedBlob, key, out_value, size); }
    STDMETHODIMP GetUnknown(REFGUID key, REFIID iid, LPVOID* out_value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetUnknown, key, iid, out_value); }
    STDMETHODIMP SetItem(REFGUID key, REFPROPVARIANT item_value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetItem, key, item_value); }
    STDMETHODIMP DeleteItem(REFGUID key) override { CAMYLLA_FORWARD_ATTRIBUTE(DeleteItem, key); }
    STDMETHODIMP DeleteAllItems() override { CAMYLLA_FORWARD_ATTRIBUTE(DeleteAllItems); }
    STDMETHODIMP SetUINT32(REFGUID key, UINT32 item_value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetUINT32, key, item_value); }
    STDMETHODIMP SetUINT64(REFGUID key, UINT64 item_value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetUINT64, key, item_value); }
    STDMETHODIMP SetDouble(REFGUID key, double item_value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetDouble, key, item_value); }
    STDMETHODIMP SetGUID(REFGUID key, REFGUID item_value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetGUID, key, item_value); }
    STDMETHODIMP SetString(REFGUID key, LPCWSTR item_value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetString, key, item_value); }
    STDMETHODIMP SetBlob(REFGUID key, const UINT8* item_value, UINT32 size) override { CAMYLLA_FORWARD_ATTRIBUTE(SetBlob, key, item_value, size); }
    STDMETHODIMP SetUnknown(REFGUID key, IUnknown* item_value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetUnknown, key, item_value); }
    STDMETHODIMP LockStore() override { CAMYLLA_FORWARD_ATTRIBUTE(LockStore); }
    STDMETHODIMP UnlockStore() override { CAMYLLA_FORWARD_ATTRIBUTE(UnlockStore); }
    STDMETHODIMP GetCount(UINT32* count) override { CAMYLLA_FORWARD_ATTRIBUTE(GetCount, count); }
    STDMETHODIMP GetItemByIndex(UINT32 index, GUID* key, PROPVARIANT* item_value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetItemByIndex, index, key, item_value); }
    STDMETHODIMP CopyAllItems(IMFAttributes* destination) override { CAMYLLA_FORWARD_ATTRIBUTE(CopyAllItems, destination); }
#undef CAMYLLA_FORWARD_ATTRIBUTE

private:
    ComPtr<IMFAttributes> attributes_;
};

HRESULT CamyllaMediaStream::Initialize(CamyllaMediaSource* parent, DWORD stream_id,
                                       IMFStreamDescriptor* descriptor) {
    ReportSourceToken();
    if (parent == nullptr || descriptor == nullptr) return E_POINTER;
    HRESULT hr = MFCreateEventQueue(&events_);
    if (FAILED(hr)) return hr;
    HRESULT parent_hr = parent->QueryInterface(IID_PPV_ARGS(&parent_));
    if (FAILED(parent_hr)) return parent_hr;
    descriptor_ = descriptor;
    if (FAILED(hr = MFCreateAttributes(&attributes_, 4))) return hr;
    if (FAILED(hr = SetFrameServerStreamAttributes(attributes_.Get(), stream_id))) return hr;
    return SetFrameServerStreamAttributes(descriptor_.Get(), stream_id);
}

HRESULT CamyllaMediaStream::Start(IMFMediaType* type) {
    if (type == nullptr) return E_POINTER;
    UINT32 width = 0;
    UINT32 height = 0;
    UINT32 numerator = 0;
    UINT32 denominator = 0;
    HRESULT hr = MFGetAttributeSize(type, MF_MT_FRAME_SIZE, &width, &height);
    if (FAILED(hr) || FAILED(hr = MFGetAttributeRatio(type, MF_MT_FRAME_RATE, &numerator, &denominator)) || denominator != 1) {
        return MF_E_INVALIDMEDIATYPE;
    }
    const VideoMode mode{width, height, numerator};
    if (!IsSupportedMode(mode)) return MF_E_INVALIDMEDIATYPE;
    std::lock_guard lock(mutex_);
    if (shutdown_) return MF_E_SHUTDOWN;
    if (!allocator_) return MF_E_NOT_INITIALIZED;
    if (FAILED(hr = allocator_->InitializeSampleAllocator(10, type))) return hr;
    mode_ = mode;
    // An explicit diagnostic switch preserves M1's deterministic offline path.
    wchar_t offline[2]{};
    provider_ = GetEnvironmentVariableW(L"CAMYLLA_SYNTHETIC", offline, 2) == 1 && offline[0] == L'1'
                    ? std::static_pointer_cast<FrameProvider>(std::make_shared<SyntheticFrameProvider>(mode))
                    : std::static_pointer_cast<FrameProvider>(std::make_shared<IpcFrameProvider>(mode));
    frame_index_ = 0;
    next_deadline_ = std::chrono::steady_clock::now();
    started_ = true;
    stream_state_ = MF_STREAM_STATE_RUNNING;
    OutputDebugStringW(L"Camylla camera: stream started with provided allocator\n");
    return events_->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, nullptr);
}

HRESULT CamyllaMediaStream::SetSampleAllocator(IMFVideoSampleAllocator* allocator) {
    std::lock_guard lock(mutex_);
    if (shutdown_) return MF_E_SHUTDOWN;
    if (started_) return MF_E_INVALIDREQUEST;
    allocator_ = allocator;
    OutputDebugStringW(allocator ? L"Camylla camera: provided allocator installed\n"
                                  : L"Camylla camera: provided allocator cleared\n");
    return S_OK;
}

HRESULT CamyllaMediaStream::Stop(bool queue_event) {
    std::lock_guard lock(mutex_);
    if (shutdown_) return MF_E_SHUTDOWN;
    started_ = false;
    stream_state_ = MF_STREAM_STATE_STOPPED;
    provider_.reset();
    return queue_event ? events_->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK, nullptr) : S_OK;
}

HRESULT CamyllaMediaStream::Shutdown() {
    std::lock_guard lock(mutex_);
    if (shutdown_) return S_OK;
    shutdown_ = true;
    started_ = false;
    stream_state_ = MF_STREAM_STATE_STOPPED;
    provider_.reset();
    allocator_.Reset();
    parent_.Reset();
    descriptor_.Reset();
    attributes_.Reset();
    return events_ ? events_->Shutdown() : S_OK;
}

STDMETHODIMP CamyllaMediaStream::BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) { return events_->BeginGetEvent(callback, state); }
STDMETHODIMP CamyllaMediaStream::EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) { return events_->EndGetEvent(result, event); }
STDMETHODIMP CamyllaMediaStream::GetEvent(DWORD flags, IMFMediaEvent** event) { return events_->GetEvent(flags, event); }
STDMETHODIMP CamyllaMediaStream::QueueEvent(MediaEventType type, REFGUID extended_type, HRESULT status, const PROPVARIANT* event_value) { return events_->QueueEventParamVar(type, extended_type, status, event_value); }
STDMETHODIMP CamyllaMediaStream::GetMediaSource(IMFMediaSource** source) { if (source == nullptr) return E_POINTER; return parent_.CopyTo(source); }
STDMETHODIMP CamyllaMediaStream::GetStreamDescriptor(IMFStreamDescriptor** descriptor) { if (descriptor == nullptr) return E_POINTER; return descriptor_.CopyTo(descriptor); }

HRESULT CamyllaMediaStream::GetAttributes(IMFAttributes** attributes) {
    if (attributes == nullptr) return E_POINTER;
    std::lock_guard lock(mutex_);
    if (shutdown_) return MF_E_SHUTDOWN;
    return attributes_.CopyTo(attributes);
}

STDMETHODIMP CamyllaMediaStream::SetStreamState(MF_STREAM_STATE state) {
    std::lock_guard lock(mutex_);
    if (shutdown_) return MF_E_SHUTDOWN;
    switch (state) {
        case MF_STREAM_STATE_PAUSED:
            if (stream_state_ != MF_STREAM_STATE_RUNNING) return MF_E_INVALID_STATE_TRANSITION;
            stream_state_ = state;
            break;
        case MF_STREAM_STATE_RUNNING:
            if (!provider_) return MF_E_INVALID_STATE_TRANSITION;
            stream_state_ = state;
            break;
        case MF_STREAM_STATE_STOPPED:
            stream_state_ = state;
            started_ = false;
            provider_.reset();
            break;
        default:
            return MF_E_INVALID_STATE_TRANSITION;
    }
    return S_OK;
}

STDMETHODIMP CamyllaMediaStream::GetStreamState(MF_STREAM_STATE* state) {
    if (state == nullptr) return E_POINTER;
    std::lock_guard lock(mutex_);
    if (shutdown_) return MF_E_SHUTDOWN;
    *state = stream_state_;
    return S_OK;
}

STDMETHODIMP CamyllaMediaStream::RequestSample(IUnknown* token) {
    const auto request = ++requests_;
    if (request <= 5 || request % 100 == 0) {
        wchar_t message[160]{};
        swprintf_s(message, L"Camylla camera: RequestSample pid=%lu requests=%llu delivered=%llu\n",
                   GetCurrentProcessId(), static_cast<unsigned long long>(request),
                   static_cast<unsigned long long>(delivered_.load()));
        OutputDebugStringW(message);
    }
    std::unique_lock lock(mutex_);
    if (shutdown_) return LogSampleFailure(L"state", MF_E_SHUTDOWN);
    if (!started_ || !provider_ || stream_state_ != MF_STREAM_STATE_RUNNING)
        return LogSampleFailure(L"state", MF_E_INVALIDREQUEST);
    const auto now = std::chrono::steady_clock::now();
    const auto deadline = next_deadline_;
    const auto frame_duration = std::chrono::nanoseconds(1'000'000'000LL / mode_.frames_per_second);
    next_deadline_ = (now > deadline ? now : deadline) + frame_duration;
    const auto frame_number = frame_index_++;
    auto provider = provider_;
    auto allocator = allocator_;
    lock.unlock();
    if (now < deadline) std::this_thread::sleep_until(deadline);
    {
        std::lock_guard state_lock(mutex_);
        if (shutdown_) return LogSampleFailure(L"state after wait", MF_E_SHUTDOWN);
        if (!started_ || stream_state_ != MF_STREAM_STATE_RUNNING)
            return LogSampleFailure(L"state after wait", MF_E_INVALIDREQUEST);
    }

    const auto frame = provider->NextFrame(frame_number);

    if (!allocator) return LogSampleFailure(L"allocator", MF_E_NOT_INITIALIZED);
    ComPtr<IMFSample> sample;
    HRESULT hr = allocator->AllocateSample(&sample);
    if (FAILED(hr)) return LogSampleFailure(L"AllocateSample", hr);
    ComPtr<IMFMediaBuffer> buffer;
    if (FAILED(hr = sample->GetBufferByIndex(0, &buffer))) return LogSampleFailure(L"GetBufferByIndex", hr);
    ComPtr<IMF2DBuffer2> buffer2d;
    if (FAILED(hr = buffer.As(&buffer2d))) return LogSampleFailure(L"IMF2DBuffer2", hr);
    BYTE* scanline = nullptr;
    BYTE* start = nullptr;
    LONG pitch = 0;
    DWORD capacity = 0;
    if (FAILED(hr = buffer2d->Lock2DSize(MF2DBuffer_LockFlags_Write, &scanline, &pitch, &start, &capacity)))
        return LogSampleFailure(L"Lock2DSize", hr);
    const auto stride = static_cast<std::size_t>(pitch);
    const auto rows = static_cast<std::size_t>(mode_.height) * 3U / 2U;
    if (pitch < static_cast<LONG>(mode_.width) || scanline < start ||
        static_cast<std::size_t>(scanline - start) + stride * rows > capacity) {
        buffer2d->Unlock2D();
        return LogSampleFailure(L"NV12 buffer capacity", MF_E_BUFFERTOOSMALL);
    }
    for (std::size_t row = 0; row < rows; ++row) {
        std::copy_n(frame.bytes.data() + row * mode_.width, mode_.width, scanline + row * stride);
    }
    if (FAILED(hr = buffer2d->Unlock2D())) return LogSampleFailure(L"Unlock2D", hr);
    if (FAILED(hr = buffer->SetCurrentLength(static_cast<DWORD>(frame.bytes.size()))) ||
        FAILED(hr = sample->SetSampleTime(MFGetSystemTime())) ||
        FAILED(hr = sample->SetSampleDuration(FrameCadence(mode_.frames_per_second).DurationFor(frame_number))))
        return LogSampleFailure(L"sample metadata", hr);
    if (token != nullptr && FAILED(hr = sample->SetUnknown(MFSampleExtension_Token, token)))
        return LogSampleFailure(L"sample token", hr);
    hr = events_->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK, sample.Get());
    if (SUCCEEDED(hr)) ++delivered_;
    else LogSampleFailure(L"MEMediaSample event", hr);
    return hr;
}

HRESULT CamyllaMediaSource::Initialize(IMFAttributes* activation_attributes) {
    HRESULT hr = MFCreateEventQueue(&events_);
    if (FAILED(hr)) return hr;
    if (FAILED(hr = MFCreateAttributes(&attributes_, 8))) return hr;
    if (activation_attributes != nullptr &&
        FAILED(hr = activation_attributes->CopyAllItems(attributes_.Get()))) return hr;
    ComPtr<IMFMediaType> media_types[2];
    for (std::size_t i = 0; i < SupportedModes().size(); ++i) {
        if (FAILED(hr = CreateMediaType(SupportedModes()[i], &media_types[i]))) return hr;
    }
    ComPtr<IMFStreamDescriptor> stream_descriptor;
    IMFMediaType* types[] = {media_types[0].Get(), media_types[1].Get()};
    if (FAILED(hr = MFCreateStreamDescriptor(0, 2, types, &stream_descriptor))) return hr;
    ComPtr<IMFMediaTypeHandler> handler;
    if (FAILED(hr = stream_descriptor->GetMediaTypeHandler(&handler)) ||
        FAILED(hr = handler->SetCurrentMediaType(media_types[0].Get())) ||
        FAILED(hr = MFCreatePresentationDescriptor(1, stream_descriptor.GetAddressOf(), &presentation_descriptor_))) return hr;
    presentation_descriptor_->SelectStream(0);
    stream_ = Microsoft::WRL::Make<CamyllaMediaStream>();
    if (!stream_) return E_OUTOFMEMORY;
    return stream_->Initialize(this, 0, stream_descriptor.Get());
}

STDMETHODIMP CamyllaMediaSource::BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) { return events_->BeginGetEvent(callback, state); }
STDMETHODIMP CamyllaMediaSource::EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) { return events_->EndGetEvent(result, event); }
STDMETHODIMP CamyllaMediaSource::GetEvent(DWORD flags, IMFMediaEvent** event) { return events_->GetEvent(flags, event); }
STDMETHODIMP CamyllaMediaSource::QueueEvent(MediaEventType type, REFGUID extended_type, HRESULT status, const PROPVARIANT* event_value) { return events_->QueueEventParamVar(type, extended_type, status, event_value); }
STDMETHODIMP CamyllaMediaSource::GetCharacteristics(DWORD* characteristics) { if (characteristics == nullptr) return E_POINTER; *characteristics = MFMEDIASOURCE_IS_LIVE; return S_OK; }

STDMETHODIMP CamyllaMediaSource::CreatePresentationDescriptor(IMFPresentationDescriptor** descriptor) {
    if (descriptor == nullptr) return E_POINTER;
    std::lock_guard lock(mutex_);
    if (state_ == SourceState::shutdown) return MF_E_SHUTDOWN;
    return presentation_descriptor_->Clone(descriptor);
}

STDMETHODIMP CamyllaMediaSource::Start(IMFPresentationDescriptor* descriptor, const GUID* time_format, const PROPVARIANT* start_position) {
    if (descriptor == nullptr || start_position == nullptr) return E_POINTER;
    if (time_format != nullptr && *time_format != GUID_NULL) return MF_E_UNSUPPORTED_TIME_FORMAT;
    BOOL selected = FALSE;
    ComPtr<IMFStreamDescriptor> stream_descriptor;
    HRESULT hr = descriptor->GetStreamDescriptorByIndex(0, &selected, &stream_descriptor);
    if (FAILED(hr)) return hr;
    if (!selected) return MF_E_INVALIDREQUEST;
    ComPtr<IMFMediaTypeHandler> handler;
    ComPtr<IMFMediaType> media_type;
    if (FAILED(hr = stream_descriptor->GetMediaTypeHandler(&handler)) || FAILED(hr = handler->GetCurrentMediaType(&media_type))) return hr;
    {
        std::lock_guard lock(mutex_);
        if (state_ == SourceState::shutdown) return MF_E_SHUTDOWN;
        state_ = SourceState::started;
    }
    if (FAILED(hr = events_->QueueEventParamUnk(MENewStream, GUID_NULL, S_OK, stream_.Get()))) return hr;
    if (FAILED(hr = stream_->Start(media_type.Get()))) return hr;
    return events_->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK, start_position);
}

STDMETHODIMP CamyllaMediaSource::Stop() {
    std::lock_guard lock(mutex_);
    if (state_ == SourceState::shutdown) return MF_E_SHUTDOWN;
    state_ = SourceState::stopped;
    HRESULT hr = stream_->Stop(true);
    return FAILED(hr) ? hr : events_->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, nullptr);
}
STDMETHODIMP CamyllaMediaSource::Pause() { return MF_E_INVALID_STATE_TRANSITION; }
STDMETHODIMP CamyllaMediaSource::Shutdown() {
    std::lock_guard lock(mutex_);
    if (state_ == SourceState::shutdown) return S_OK;
    state_ = SourceState::shutdown;
    const HRESULT stream_hr = stream_ ? stream_->Shutdown() : S_OK;
    stream_.Reset(); presentation_descriptor_.Reset(); attributes_.Reset();
    const HRESULT queue_hr = events_ ? events_->Shutdown() : S_OK;
    return FAILED(stream_hr) ? stream_hr : queue_hr;
}
STDMETHODIMP CamyllaMediaSource::GetSourceAttributes(IMFAttributes** attributes) { if (attributes == nullptr) return E_POINTER; std::lock_guard lock(mutex_); return state_ == SourceState::shutdown ? MF_E_SHUTDOWN : attributes_.CopyTo(attributes); }
STDMETHODIMP CamyllaMediaSource::GetStreamAttributes(DWORD stream_identifier, IMFAttributes** attributes) {
    if (attributes == nullptr) return E_POINTER;
    *attributes = nullptr;
    std::lock_guard lock(mutex_);
    if (state_ == SourceState::shutdown) return MF_E_SHUTDOWN;
    if (stream_identifier != 0) return MF_E_INVALIDSTREAMNUMBER;
    return stream_->GetAttributes(attributes);
}
STDMETHODIMP CamyllaMediaSource::SetD3DManager(IUnknown* manager) { UNREFERENCED_PARAMETER(manager); return S_OK; }

STDMETHODIMP CamyllaMediaSource::GetService(REFGUID service, REFIID iid, LPVOID* object) {
    UNREFERENCED_PARAMETER(service);
    UNREFERENCED_PARAMETER(iid);
    if (object == nullptr) return E_POINTER;
    *object = nullptr;
    return MF_E_UNSUPPORTED_SERVICE;
}

STDMETHODIMP CamyllaMediaSource::KsProperty(PKSPROPERTY property, ULONG property_length,
                                             LPVOID property_data, ULONG data_length,
                                             ULONG* bytes_returned) {
    UNREFERENCED_PARAMETER(property);
    UNREFERENCED_PARAMETER(property_length);
    UNREFERENCED_PARAMETER(property_data);
    UNREFERENCED_PARAMETER(data_length);
    if (bytes_returned != nullptr) *bytes_returned = 0;
    return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

STDMETHODIMP CamyllaMediaSource::KsMethod(PKSMETHOD method, ULONG method_length,
                                           LPVOID method_data, ULONG data_length,
                                           ULONG* bytes_returned) {
    UNREFERENCED_PARAMETER(method);
    UNREFERENCED_PARAMETER(method_length);
    UNREFERENCED_PARAMETER(method_data);
    UNREFERENCED_PARAMETER(data_length);
    if (bytes_returned != nullptr) *bytes_returned = 0;
    return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

STDMETHODIMP CamyllaMediaSource::KsEvent(PKSEVENT event, ULONG event_length,
                                          LPVOID event_data, ULONG data_length,
                                          ULONG* bytes_returned) {
    UNREFERENCED_PARAMETER(event);
    UNREFERENCED_PARAMETER(event_length);
    UNREFERENCED_PARAMETER(event_data);
    UNREFERENCED_PARAMETER(data_length);
    if (bytes_returned != nullptr) *bytes_returned = 0;
    return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

STDMETHODIMP CamyllaMediaSource::GetAllocatorUsage(DWORD stream_id, DWORD* input_stream_id,
                                                    MFSampleAllocatorUsage* usage) {
    if (input_stream_id == nullptr || usage == nullptr) return E_POINTER;
    std::lock_guard lock(mutex_);
    if (state_ == SourceState::shutdown) return MF_E_SHUTDOWN;
    if (stream_id != 0) return MF_E_INVALIDSTREAMNUMBER;
    *input_stream_id = stream_id;
    *usage = MFSampleAllocatorUsage_UsesProvidedAllocator;
    return S_OK;
}

STDMETHODIMP CamyllaMediaSource::SetDefaultAllocator(DWORD stream_id, IUnknown* allocator) {
    std::lock_guard lock(mutex_);
    if (state_ == SourceState::shutdown) return MF_E_SHUTDOWN;
    if (stream_id != 0) return MF_E_INVALIDSTREAMNUMBER;
    ComPtr<IMFVideoSampleAllocator> video_allocator;
    if (allocator != nullptr) {
        const HRESULT hr = allocator->QueryInterface(IID_PPV_ARGS(&video_allocator));
        if (FAILED(hr)) return hr;
    }
    return stream_->SetSampleAllocator(video_allocator.Get());
}

}  // namespace

HRESULT CreateActivation(REFIID iid, void** object) noexcept {
    if (object == nullptr) return E_POINTER;
    *object = nullptr;
    auto activate = Microsoft::WRL::Make<CamyllaActivate>();
    if (!activate) return E_OUTOFMEMORY;
    const HRESULT hr = activate->Initialize();
    return SUCCEEDED(hr) ? activate.CopyTo(iid, object) : hr;
}

}  // namespace camylla::virtual_camera::win
