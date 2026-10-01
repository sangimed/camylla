#include "media_source.h"

#include <windows.h>
#include <wrl.h>

using Microsoft::WRL::ComPtr;
using Microsoft::WRL::RuntimeClass;
using Microsoft::WRL::RuntimeClassFlags;
using Microsoft::WRL::ClassicCom;

namespace {

class SourceClassFactory final : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IClassFactory> {
public:
    STDMETHODIMP CreateInstance(IUnknown* outer, REFIID iid, void** object) override {
        if (outer != nullptr) return CLASS_E_NOAGGREGATION;
        return camylla::virtual_camera::win::CreateActivation(iid, object);
    }
    STDMETHODIMP LockServer(BOOL lock) override {
        UNREFERENCED_PARAMETER(lock);
        return S_OK;
    }
};

}  // namespace

extern "C" BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    UNREFERENCED_PARAMETER(instance);
    UNREFERENCED_PARAMETER(reason);
    UNREFERENCED_PARAMETER(reserved);
    return TRUE;
}

extern "C" __declspec(dllexport) HRESULT WINAPI DllCanUnloadNow() { return S_FALSE; }

extern "C" __declspec(dllexport) HRESULT WINAPI DllGetClassObject(REFCLSID clsid, REFIID iid, LPVOID* object) {
    if (object == nullptr) return E_POINTER;
    *object = nullptr;
    if (clsid != camylla::virtual_camera::win::kSourceClsid) return CLASS_E_CLASSNOTAVAILABLE;
    ComPtr<SourceClassFactory> factory = Microsoft::WRL::Make<SourceClassFactory>();
    if (!factory) return E_OUTOFMEMORY;
    return factory.CopyTo(iid, object);
}
