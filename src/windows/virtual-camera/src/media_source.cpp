#include "media_source.h"

#include "camylla/virtual_camera/cadence.h"
#include "camylla/virtual_camera/synthetic_frame_provider.h"
#include "camylla/virtual_camera/video_mode.h"

#include <mfapi.h>
#include <mferror.h>
#include <mfobjects.h>
#include <wrl/client.h>

#include <chrono>
#include <algorithm>
#include <memory>
#include <mutex>
#include <thread>

using Microsoft::WRL::ComPtr;
using Microsoft::WRL::RuntimeClass;
using Microsoft::WRL::RuntimeClassFlags;
using Microsoft::WRL::ClassicCom;

namespace camylla::virtual_camera::win {

const CLSID kSourceClsid = {0xca9f0b23, 0xc7e0, 0x42c7, {0x94, 0xb4, 0x2d, 0x6a, 0x5f, 0xad, 0x54, 0x4d}};

namespace {

enum class SourceState { stopped, started, shutdown };

class CamyllaMediaSource;

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
    : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IMFMediaStream> {
public:
    HRESULT Initialize(CamyllaMediaSource* parent, DWORD stream_id, IMFStreamDescriptor* descriptor);
    HRESULT Start(IMFMediaType* type);
    HRESULT Stop(bool queue_event);
    HRESULT Shutdown();

    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) override;
    STDMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override;
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override;
    STDMETHODIMP QueueEvent(MediaEventType type, REFGUID extended_type, HRESULT status,
                            const PROPVARIANT* value) override;
    STDMETHODIMP GetMediaSource(IMFMediaSource** source) override;
    STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** descriptor) override;
    STDMETHODIMP RequestSample(IUnknown* token) override;

private:
    std::mutex mutex_;
    ComPtr<IMFMediaSource> parent_;
    ComPtr<IMFMediaEventQueue> events_;
    ComPtr<IMFStreamDescriptor> descriptor_;
    std::unique_ptr<SyntheticFrameProvider> provider_;
    VideoMode mode_{};
    std::uint64_t frame_index_{};
    bool started_{};
    bool shutdown_{};
    std::chrono::steady_clock::time_point next_deadline_{};
};

class CamyllaMediaSource final
    : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IMFMediaSourceEx> {
public:
    HRESULT Initialize();

    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) override;
    STDMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override;
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override;
    STDMETHODIMP QueueEvent(MediaEventType type, REFGUID extended_type, HRESULT status,
                            const PROPVARIANT* value) override;
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

private:
    std::mutex mutex_;
    SourceState state_{SourceState::stopped};
    ComPtr<IMFMediaEventQueue> events_;
    ComPtr<IMFPresentationDescriptor> presentation_descriptor_;
    ComPtr<IMFAttributes> attributes_;
    ComPtr<CamyllaMediaStream> stream_;
};

class CamyllaActivate final : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IMFActivate> {
public:
    HRESULT Initialize() { return MFCreateAttributes(&attributes_, 0); }

    STDMETHODIMP ActivateObject(REFIID iid, void** object) override {
        if (object == nullptr) return E_POINTER;
        *object = nullptr;
        ComPtr<CamyllaMediaSource> source = Microsoft::WRL::Make<CamyllaMediaSource>();
        if (!source) return E_OUTOFMEMORY;
        HRESULT hr = source->Initialize();
        return SUCCEEDED(hr) ? source.CopyTo(iid, object) : hr;
    }
    STDMETHODIMP ShutdownObject() override { return S_OK; }
    STDMETHODIMP DetachObject() override { return S_OK; }

#define CAMYLLA_FORWARD_ATTRIBUTE(method, ...) return attributes_->method(__VA_ARGS__)
    STDMETHODIMP GetItem(REFGUID key, PROPVARIANT* value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetItem, key, value); }
    STDMETHODIMP GetItemType(REFGUID key, MF_ATTRIBUTE_TYPE* type) override { CAMYLLA_FORWARD_ATTRIBUTE(GetItemType, key, type); }
    STDMETHODIMP CompareItem(REFGUID key, REFPROPVARIANT value, BOOL* result) override { CAMYLLA_FORWARD_ATTRIBUTE(CompareItem, key, value, result); }
    STDMETHODIMP Compare(IMFAttributes* theirs, MF_ATTRIBUTES_MATCH_TYPE type, BOOL* result) override { CAMYLLA_FORWARD_ATTRIBUTE(Compare, theirs, type, result); }
    STDMETHODIMP GetUINT32(REFGUID key, UINT32* value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetUINT32, key, value); }
    STDMETHODIMP GetUINT64(REFGUID key, UINT64* value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetUINT64, key, value); }
    STDMETHODIMP GetDouble(REFGUID key, double* value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetDouble, key, value); }
    STDMETHODIMP GetGUID(REFGUID key, GUID* value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetGUID, key, value); }
    STDMETHODIMP GetStringLength(REFGUID key, UINT32* length) override { CAMYLLA_FORWARD_ATTRIBUTE(GetStringLength, key, length); }
    STDMETHODIMP GetString(REFGUID key, LPWSTR value, UINT32 size, UINT32* length) override { CAMYLLA_FORWARD_ATTRIBUTE(GetString, key, value, size, length); }
    STDMETHODIMP GetAllocatedString(REFGUID key, LPWSTR* value, UINT32* length) override { CAMYLLA_FORWARD_ATTRIBUTE(GetAllocatedString, key, value, length); }
    STDMETHODIMP GetBlobSize(REFGUID key, UINT32* size) override { CAMYLLA_FORWARD_ATTRIBUTE(GetBlobSize, key, size); }
    STDMETHODIMP GetBlob(REFGUID key, UINT8* value, UINT32 size, UINT32* written) override { CAMYLLA_FORWARD_ATTRIBUTE(GetBlob, key, value, size, written); }
    STDMETHODIMP GetAllocatedBlob(REFGUID key, UINT8** value, UINT32* size) override { CAMYLLA_FORWARD_ATTRIBUTE(GetAllocatedBlob, key, value, size); }
    STDMETHODIMP GetUnknown(REFGUID key, REFIID iid, LPVOID* value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetUnknown, key, iid, value); }
    STDMETHODIMP SetItem(REFGUID key, REFPROPVARIANT value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetItem, key, value); }
    STDMETHODIMP DeleteItem(REFGUID key) override { CAMYLLA_FORWARD_ATTRIBUTE(DeleteItem, key); }
    STDMETHODIMP DeleteAllItems() override { CAMYLLA_FORWARD_ATTRIBUTE(DeleteAllItems); }
    STDMETHODIMP SetUINT32(REFGUID key, UINT32 value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetUINT32, key, value); }
    STDMETHODIMP SetUINT64(REFGUID key, UINT64 value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetUINT64, key, value); }
    STDMETHODIMP SetDouble(REFGUID key, double value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetDouble, key, value); }
    STDMETHODIMP SetGUID(REFGUID key, REFGUID value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetGUID, key, value); }
    STDMETHODIMP SetString(REFGUID key, LPCWSTR value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetString, key, value); }
    STDMETHODIMP SetBlob(REFGUID key, const UINT8* value, UINT32 size) override { CAMYLLA_FORWARD_ATTRIBUTE(SetBlob, key, value, size); }
    STDMETHODIMP SetUnknown(REFGUID key, IUnknown* value) override { CAMYLLA_FORWARD_ATTRIBUTE(SetUnknown, key, value); }
    STDMETHODIMP LockStore() override { CAMYLLA_FORWARD_ATTRIBUTE(LockStore); }
    STDMETHODIMP UnlockStore() override { CAMYLLA_FORWARD_ATTRIBUTE(UnlockStore); }
    STDMETHODIMP GetCount(UINT32* count) override { CAMYLLA_FORWARD_ATTRIBUTE(GetCount, count); }
    STDMETHODIMP GetItemByIndex(UINT32 index, GUID* key, PROPVARIANT* value) override { CAMYLLA_FORWARD_ATTRIBUTE(GetItemByIndex, index, key, value); }
    STDMETHODIMP CopyAllItems(IMFAttributes* destination) override { CAMYLLA_FORWARD_ATTRIBUTE(CopyAllItems, destination); }
#undef CAMYLLA_FORWARD_ATTRIBUTE

private:
    ComPtr<IMFAttributes> attributes_;
};

HRESULT CamyllaMediaStream::Initialize(CamyllaMediaSource* parent, DWORD stream_id,
                                       IMFStreamDescriptor* descriptor) {
    if (parent == nullptr || descriptor == nullptr) return E_POINTER;
    HRESULT hr = MFCreateEventQueue(&events_);
    if (FAILED(hr)) return hr;
    HRESULT parent_hr = parent->QueryInterface(IID_PPV_ARGS(&parent_));
    if (FAILED(parent_hr)) return parent_hr;
    descriptor_ = descriptor;
    UNREFERENCED_PARAMETER(stream_id);
    return S_OK;
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
    mode_ = mode;
    provider_ = std::make_unique<SyntheticFrameProvider>(mode);
    frame_index_ = 0;
    next_deadline_ = std::chrono::steady_clock::now();
    started_ = true;
    return events_->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, nullptr);
}

HRESULT CamyllaMediaStream::Stop(bool queue_event) {
    std::lock_guard lock(mutex_);
    if (shutdown_) return MF_E_SHUTDOWN;
    started_ = false;
    provider_.reset();
    return queue_event ? events_->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK, nullptr) : S_OK;
}

HRESULT CamyllaMediaStream::Shutdown() {
    std::lock_guard lock(mutex_);
    if (shutdown_) return S_OK;
    shutdown_ = true;
    started_ = false;
    provider_.reset();
    parent_.Reset();
    descriptor_.Reset();
    return events_ ? events_->Shutdown() : S_OK;
}

STDMETHODIMP CamyllaMediaStream::BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) { return events_->BeginGetEvent(callback, state); }
STDMETHODIMP CamyllaMediaStream::EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) { return events_->EndGetEvent(result, event); }
STDMETHODIMP CamyllaMediaStream::GetEvent(DWORD flags, IMFMediaEvent** event) { return events_->GetEvent(flags, event); }
STDMETHODIMP CamyllaMediaStream::QueueEvent(MediaEventType type, REFGUID extended_type, HRESULT status, const PROPVARIANT* value) { return events_->QueueEventParamVar(type, extended_type, status, value); }
STDMETHODIMP CamyllaMediaStream::GetMediaSource(IMFMediaSource** source) { if (source == nullptr) return E_POINTER; return parent_.CopyTo(source); }
STDMETHODIMP CamyllaMediaStream::GetStreamDescriptor(IMFStreamDescriptor** descriptor) { if (descriptor == nullptr) return E_POINTER; return descriptor_.CopyTo(descriptor); }

STDMETHODIMP CamyllaMediaStream::RequestSample(IUnknown* token) {
    std::unique_lock lock(mutex_);
    if (shutdown_) return MF_E_SHUTDOWN;
    if (!started_ || !provider_) return MF_E_INVALIDREQUEST;
    const auto now = std::chrono::steady_clock::now();
    const auto deadline = next_deadline_;
    const auto frame_duration = std::chrono::nanoseconds(1'000'000'000LL / mode_.frames_per_second);
    next_deadline_ = (now > deadline ? now : deadline) + frame_duration;
    const auto frame = provider_->NextFrame(frame_index_++);
    lock.unlock();
    if (now < deadline) std::this_thread::sleep_until(deadline);
    {
        std::lock_guard state_lock(mutex_);
        if (shutdown_) return MF_E_SHUTDOWN;
        if (!started_) return MF_E_INVALIDREQUEST;
    }

    ComPtr<IMFMediaBuffer> buffer;
    HRESULT hr = MFCreateMemoryBuffer(static_cast<DWORD>(frame.bytes.size()), &buffer);
    if (FAILED(hr)) return hr;
    BYTE* bytes = nullptr;
    DWORD capacity = 0;
    if (FAILED(hr = buffer->Lock(&bytes, &capacity, nullptr))) return hr;
    if (capacity < frame.bytes.size()) { buffer->Unlock(); return E_UNEXPECTED; }
    std::copy(frame.bytes.begin(), frame.bytes.end(), bytes);
    buffer->Unlock();
    if (FAILED(hr = buffer->SetCurrentLength(static_cast<DWORD>(frame.bytes.size())))) return hr;
    ComPtr<IMFSample> sample;
    if (FAILED(hr = MFCreateSample(&sample)) || FAILED(hr = sample->AddBuffer(buffer.Get())) ||
        FAILED(hr = sample->SetSampleTime(frame.timestamp_100ns)) ||
        FAILED(hr = sample->SetSampleDuration(FrameCadence(mode_.frames_per_second).DurationFor(frame_index_ - 1U)))) return hr;
    if (token != nullptr && FAILED(hr = sample->SetUnknown(MFSampleExtension_Token, token))) return hr;
    return events_->QueueEventParamUnk(MEStreamSample, GUID_NULL, S_OK, sample.Get());
}

HRESULT CamyllaMediaSource::Initialize() {
    HRESULT hr = MFCreateEventQueue(&events_);
    if (FAILED(hr)) return hr;
    if (FAILED(hr = MFCreateAttributes(&attributes_, 0))) return hr;
    ComPtr<IMFMediaType> media_types[2];
    for (std::size_t i = 0; i < SupportedModes().size(); ++i) {
        if (FAILED(hr = CreateMediaType(SupportedModes()[i], &media_types[i]))) return hr;
    }
    ComPtr<IMFStreamDescriptor> stream_descriptor;
    if (FAILED(hr = MFCreateStreamDescriptor(0, 2, media_types[0].GetAddressOf(), &stream_descriptor)) ||
        FAILED(hr = MFCreatePresentationDescriptor(1, stream_descriptor.GetAddressOf(), &presentation_descriptor_))) return hr;
    presentation_descriptor_->SelectStream(0);
    stream_ = Microsoft::WRL::Make<CamyllaMediaStream>();
    if (!stream_) return E_OUTOFMEMORY;
    return stream_->Initialize(this, 0, stream_descriptor.Get());
}

STDMETHODIMP CamyllaMediaSource::BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) { return events_->BeginGetEvent(callback, state); }
STDMETHODIMP CamyllaMediaSource::EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) { return events_->EndGetEvent(result, event); }
STDMETHODIMP CamyllaMediaSource::GetEvent(DWORD flags, IMFMediaEvent** event) { return events_->GetEvent(flags, event); }
STDMETHODIMP CamyllaMediaSource::QueueEvent(MediaEventType type, REFGUID extended_type, HRESULT status, const PROPVARIANT* value) { return events_->QueueEventParamVar(type, extended_type, status, value); }
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
STDMETHODIMP CamyllaMediaSource::GetStreamAttributes(DWORD stream_identifier, IMFAttributes** attributes) { UNREFERENCED_PARAMETER(stream_identifier); if (attributes == nullptr) return E_POINTER; *attributes = nullptr; std::lock_guard lock(mutex_); return state_ == SourceState::shutdown ? MF_E_SHUTDOWN : E_NOTIMPL; }
STDMETHODIMP CamyllaMediaSource::SetD3DManager(IUnknown* manager) { UNREFERENCED_PARAMETER(manager); return S_OK; }

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
