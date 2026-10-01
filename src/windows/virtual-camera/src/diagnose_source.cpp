#include "diagnose_source.h"
#include "media_source.h"

#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <ks.h>
#include <ksproxy.h>
#include <wrl/client.h>

#include <iostream>
#include <memory>

using Microsoft::WRL::ComPtr;

namespace {
const CLSID kSourceClsid = {0xca9f0b23, 0xc7e0, 0x42c7, {0x94, 0xb4, 0x2d, 0x6a, 0x5f, 0xad, 0x54, 0x4d}};

void Report(const wchar_t* step, HRESULT hr, REFIID iid = GUID_NULL) {
    std::wcout << (SUCCEEDED(hr) ? L"PASS " : L"FAIL ") << step
               << L" HRESULT=0x" << std::hex << static_cast<unsigned long>(hr);
    if (iid != GUID_NULL) {
        wchar_t id[39]{};
        StringFromGUID2(iid, id, 39);
        std::wcout << L" IID=" << id;
    }
    std::wcout << std::dec << L'\n';
}

template <typename T>
HRESULT CheckInterface(IUnknown* object, const wchar_t* name, ComPtr<T>* result) {
    const HRESULT hr = object->QueryInterface(IID_PPV_ARGS(result->ReleaseAndGetAddressOf()));
    Report(name, hr, __uuidof(T));
    return hr;
}

void DumpAttributes(IMFAttributes* attributes, const wchar_t* name) {
    UINT32 count = 0;
    const HRESULT hr = attributes->GetCount(&count);
    Report(name, hr);
    if (FAILED(hr)) return;
    for (UINT32 index = 0; index < count; ++index) {
        GUID key{};
        PROPVARIANT value{};
        PropVariantInit(&value);
        const HRESULT item_hr = attributes->GetItemByIndex(index, &key, &value);
        if (SUCCEEDED(item_hr)) {
            wchar_t id[39]{};
            StringFromGUID2(key, id, 39);
            std::wcout << L"  " << id << L" type=" << value.vt;
            if (value.vt == VT_UI4) std::wcout << L" value=" << value.ulVal;
            if (value.vt == VT_LPWSTR) std::wcout << L" value=" << value.pwszVal;
            std::wcout << L'\n';
        } else {
            Report(L"IMFAttributes::GetItemByIndex", item_hr);
        }
        PropVariantClear(&value);
    }
}

}  // namespace

HRESULT DiagnoseSource(const std::filesystem::path& dll_path) {
    const auto key_path = std::wstring(L"Software\\Classes\\CLSID\\") +
        camylla::virtual_camera::win::kSourceClsidText + L"\\InprocServer32";
    wchar_t registered[MAX_PATH]{};
    DWORD bytes = sizeof(registered);
    const LONG registry_result = RegGetValueW(HKEY_LOCAL_MACHINE, key_path.c_str(), nullptr,
                                               RRF_RT_REG_SZ | RRF_SUBKEY_WOW6464KEY,
                                               nullptr, registered, &bytes);
    Report(L"HKLM x64 InprocServer32", HRESULT_FROM_WIN32(registry_result));
    if (registry_result == ERROR_SUCCESS) std::wcout << L"  DLL=" << registered << L'\n';
    wchar_t threading[32]{};
    bytes = sizeof(threading);
    const LONG model_result = RegGetValueW(HKEY_LOCAL_MACHINE, key_path.c_str(), L"ThreadingModel",
                                            RRF_RT_REG_SZ | RRF_SUBKEY_WOW6464KEY,
                                            nullptr, threading, &bytes);
    Report(L"HKLM x64 ThreadingModel", HRESULT_FROM_WIN32(model_result));
    if (model_result == ERROR_SUCCESS) std::wcout << L"  Model=" << threading << L'\n';

    ComPtr<IMFActivate> activate;
    HRESULT hr = CoCreateInstance(kSourceClsid, nullptr,
                                  CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&activate));
    Report(L"CoCreateInstance(IMFActivate)", hr, __uuidof(IMFActivate));

    // Independently test the DLL's export and factory, even if registry activation worked.
    HMODULE module = LoadLibraryW(dll_path.c_str());
    Report(L"LoadLibrary(source DLL)", module ? S_OK : HRESULT_FROM_WIN32(GetLastError()));
    if (module) {
        using FactoryFunction = HRESULT(STDAPICALLTYPE*)(REFCLSID, REFIID, void**);
        auto export_function = reinterpret_cast<FactoryFunction>(GetProcAddress(module, "DllGetClassObject"));
        Report(L"GetProcAddress(DllGetClassObject)", export_function ? S_OK : HRESULT_FROM_WIN32(GetLastError()));
        if (export_function) {
            ComPtr<IClassFactory> factory;
            hr = export_function(kSourceClsid,
                                 IID_PPV_ARGS(&factory));
            Report(L"DllGetClassObject(IClassFactory)", hr, __uuidof(IClassFactory));
            if (SUCCEEDED(hr)) {
                ComPtr<IMFActivate> direct;
                hr = factory->CreateInstance(nullptr, IID_PPV_ARGS(&direct));
                Report(L"IClassFactory::CreateInstance(IMFActivate)", hr, __uuidof(IMFActivate));
                if (!activate && SUCCEEDED(hr)) activate = direct;
            }
        }
        // Keep the module loaded while COM objects from it may still exist.
    }
    if (!activate) return E_NOINTERFACE;
    ComPtr<IMFAttributes> activation_attributes;
    CheckInterface(activate.Get(), L"activation IMFAttributes", std::addressof(activation_attributes));
    if (!activation_attributes) return E_NOINTERFACE;
    DumpAttributes(activation_attributes.Get(), L"activation attributes");

    ComPtr<IMFMediaSource> source;
    hr = activate->ActivateObject(IID_PPV_ARGS(&source));
    Report(L"IMFActivate::ActivateObject(IMFMediaSource)", hr, __uuidof(IMFMediaSource));
    if (FAILED(hr)) return hr;
    ComPtr<IMFMediaSourceEx> source_ex;
    ComPtr<IMFGetService> service;
    ComPtr<IKsControl> ks;
    ComPtr<IMFSampleAllocatorControl> allocator;
    CheckInterface(source.Get(), L"source IMFMediaSourceEx", std::addressof(source_ex));
    CheckInterface(source.Get(), L"source IMFGetService", std::addressof(service));
    CheckInterface(source.Get(), L"source IKsControl", std::addressof(ks));
    CheckInterface(source.Get(), L"source IMFSampleAllocatorControl", std::addressof(allocator));
    if (!source_ex || !service || !ks || !allocator) return E_NOINTERFACE;
    if (source_ex) {
        ComPtr<IMFAttributes> attributes;
        hr = source_ex->GetSourceAttributes(&attributes);
        Report(L"IMFMediaSourceEx::GetSourceAttributes", hr);
        if (SUCCEEDED(hr)) DumpAttributes(attributes.Get(), L"source attributes");
    }
    ComPtr<IMFPresentationDescriptor> descriptor;
    hr = source->CreatePresentationDescriptor(&descriptor);
    Report(L"IMFMediaSource::CreatePresentationDescriptor", hr);
    if (FAILED(hr)) return hr;
    DWORD count = 0;
    hr = descriptor->GetStreamDescriptorCount(&count);
    Report(L"IMFPresentationDescriptor::GetStreamDescriptorCount", hr);
    if (FAILED(hr)) return hr;
    std::wcout << L"  streams=" << count << L'\n';
    for (DWORD index = 0; index < count; ++index) {
        BOOL selected = FALSE;
        ComPtr<IMFStreamDescriptor> stream_descriptor;
        hr = descriptor->GetStreamDescriptorByIndex(index, &selected, &stream_descriptor);
        Report(L"GetStreamDescriptorByIndex", hr);
        if (FAILED(hr)) continue;
        DWORD stream_id = 0;
        hr = stream_descriptor->GetStreamIdentifier(&stream_id);
        Report(L"GetStreamIdentifier", hr);
        std::wcout << L"  stream=" << stream_id << L" selected=" << selected << L'\n';
        DumpAttributes(stream_descriptor.Get(), L"stream descriptor attributes");
        ComPtr<IMFMediaTypeHandler> handler;
        hr = stream_descriptor->GetMediaTypeHandler(&handler);
        Report(L"GetMediaTypeHandler", hr);
        if (SUCCEEDED(hr)) {
            DWORD type_count = 0;
            hr = handler->GetMediaTypeCount(&type_count);
            Report(L"GetMediaTypeCount", hr);
            std::wcout << L"  media types=" << type_count << L'\n';
            for (DWORD type_index = 0; SUCCEEDED(hr) && type_index < type_count; ++type_index) {
                ComPtr<IMFMediaType> type;
                hr = handler->GetMediaTypeByIndex(type_index, &type);
                Report(L"GetMediaTypeByIndex", hr);
                if (SUCCEEDED(hr)) DumpAttributes(type.Get(), L"media type attributes");
            }
        }
        if (source_ex) {
            ComPtr<IMFAttributes> stream_attributes;
            hr = source_ex->GetStreamAttributes(stream_id, &stream_attributes);
            Report(L"IMFMediaSourceEx::GetStreamAttributes", hr);
            if (SUCCEEDED(hr)) DumpAttributes(stream_attributes.Get(), L"stream attributes");
        }
        if (allocator) {
            DWORD input_id = 0;
            MFSampleAllocatorUsage usage{};
            hr = allocator->GetAllocatorUsage(stream_id, &input_id, &usage);
            Report(L"IMFSampleAllocatorControl::GetAllocatorUsage", hr);
            if (SUCCEEDED(hr)) std::wcout << L"  usage=" << usage << L'\n';
        }
    }
    PROPVARIANT position{};
    PropVariantInit(&position);
    hr = source->Start(descriptor.Get(), nullptr, &position);
    Report(L"IMFMediaSource::Start", hr);
    HRESULT diagnosis_hr = hr;
    if (SUCCEEDED(hr)) {
        ComPtr<IMFMediaEvent> event;
        hr = source->GetEvent(MF_EVENT_FLAG_NO_WAIT, &event);
        Report(L"IMFMediaSource::GetEvent", hr);
        if (SUCCEEDED(hr)) {
            ComPtr<IUnknown> stream_unknown;
            PROPVARIANT value{};
            PropVariantInit(&value);
            hr = event->GetValue(&value);
            if (SUCCEEDED(hr) && value.vt == VT_UNKNOWN && value.punkVal != nullptr) {
                stream_unknown = value.punkVal;
            } else if (SUCCEEDED(hr)) {
                hr = E_NOINTERFACE;
            }
            Report(L"MENewStream event object", hr);
            if (SUCCEEDED(hr)) {
                ComPtr<IMFMediaStream> stream;
                ComPtr<IMFMediaStream2> stream2;
                CheckInterface(stream_unknown.Get(), L"stream IMFMediaStream", std::addressof(stream));
                CheckInterface(stream_unknown.Get(), L"stream IMFMediaStream2", std::addressof(stream2));
                if (!stream || !stream2) diagnosis_hr = E_NOINTERFACE;
            }
            PropVariantClear(&value);
        }
        source->Stop();
    }
    source->Shutdown();
    return diagnosis_hr;
}
