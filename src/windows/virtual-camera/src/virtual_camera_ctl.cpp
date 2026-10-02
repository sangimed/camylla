#include "media_source.h"
#include "diagnose_source.h"

#include <mfapi.h>
#include <mfvirtualcamera.h>
#include <windows.h>

#include <filesystem>
#include <iostream>
#include <optional>
#include <string>
#include <string_view>

namespace {

constexpr wchar_t kFriendlyName[] = L"Camylla POC";

enum class Scope { current_user, all_users };

void PrintFailure(std::wstring_view operation, HRESULT hr) {
    std::wcerr << operation << L" failed (HRESULT 0x" << std::hex
               << static_cast<unsigned long>(hr) << std::dec << L")";
    wchar_t* message = nullptr;
    const DWORD length = FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
                                            FORMAT_MESSAGE_IGNORE_INSERTS,
                                        nullptr, static_cast<DWORD>(hr), 0,
                                        reinterpret_cast<wchar_t*>(&message), 0, nullptr);
    if (length != 0) {
        std::wcerr << L": " << std::wstring_view(message, length);
        LocalFree(message);
    } else {
        std::wcerr << L".\n";
    }
}

void RecordFailure(std::wstring_view operation, HRESULT result, HRESULT& hr,
                   std::wstring_view& failure_operation) {
    if (FAILED(result)) {
        if (FAILED(hr)) {
            PrintFailure(operation, result);
        } else {
            hr = result;
            failure_operation = operation;
        }
    }
}

std::optional<Scope> ParseScope(std::wstring_view value) {
    if (value == L"current-user") return Scope::current_user;
    if (value == L"all-users") return Scope::all_users;
    return std::nullopt;
}

std::wstring ClassesPath() {
    return std::wstring(L"Software\\Classes\\CLSID\\") +
           camylla::virtual_camera::win::kSourceClsidText;
}

HRESULT WriteComRegistration(const std::filesystem::path& source_dll) {
    HKEY class_key = nullptr;
    const auto path = ClassesPath() + L"\\InprocServer32";
    const LONG create_result = RegCreateKeyExW(HKEY_LOCAL_MACHINE, path.c_str(), 0, nullptr,
                                                REG_OPTION_NON_VOLATILE, KEY_SET_VALUE, nullptr,
                                                &class_key, nullptr);
    if (create_result != ERROR_SUCCESS) return HRESULT_FROM_WIN32(create_result);
    const auto source_text = source_dll.wstring();
    const LONG dll_result = RegSetValueExW(class_key, nullptr, 0, REG_SZ,
                                           reinterpret_cast<const BYTE*>(source_text.c_str()),
                                           static_cast<DWORD>((source_text.size() + 1U) * sizeof(wchar_t)));
    const wchar_t threading_model[] = L"Both";
    const LONG threading_result = dll_result == ERROR_SUCCESS
        ? RegSetValueExW(class_key, L"ThreadingModel", 0, REG_SZ,
                         reinterpret_cast<const BYTE*>(threading_model), sizeof(threading_model))
        : dll_result;
    RegCloseKey(class_key);
    return HRESULT_FROM_WIN32(threading_result);
}

HRESULT RemoveComRegistration() {
    const LONG machine_result = RegDeleteTreeW(HKEY_LOCAL_MACHINE, ClassesPath().c_str());
    if (machine_result != ERROR_SUCCESS && machine_result != ERROR_FILE_NOT_FOUND) {
        return HRESULT_FROM_WIN32(machine_result);
    }

    // Clean up the legacy per-user registration used by the first POC revisions.
    const LONG user_result = RegDeleteTreeW(HKEY_CURRENT_USER, ClassesPath().c_str());
    return user_result == ERROR_SUCCESS || user_result == ERROR_FILE_NOT_FOUND
        ? S_OK
        : HRESULT_FROM_WIN32(user_result);
}

HRESULT OpenCamera(Scope scope, IMFVirtualCamera** camera) {
    const auto access = scope == Scope::current_user ? MFVirtualCameraAccess_CurrentUser
                                                       : MFVirtualCameraAccess_AllUsers;
    return MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource,
                                 MFVirtualCameraLifetime_System, access, kFriendlyName,
                                 camylla::virtual_camera::win::kSourceClsidText, nullptr, 0, camera);
}

std::filesystem::path DefaultSourcePath(const wchar_t* executable_path) {
    return std::filesystem::path(executable_path).parent_path() / L"camylla_virtual_camera_source.dll";
}

void PrintUsage() {
    std::wcerr << L"Usage:\n"
               << L"  camylla_virtual_camera_ctl register [--scope current-user|all-users] [--source path]\n"
               << L"  camylla_virtual_camera_ctl unregister [--scope current-user|all-users]\n"
               << L"  camylla_virtual_camera_ctl diagnose-source [--source path]\n"
               << L"\nThe media-source COM DLL is registered machine-wide under HKLM so the "
               << L"Windows Frame Server service can activate it. Run register/unregister from "
               << L"an elevated terminal. --scope controls virtual-camera access only.\n";
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc < 2) { PrintUsage(); return 2; }
    const std::wstring_view command(argv[1]);
    Scope scope = Scope::current_user;
    std::optional<std::filesystem::path> source_path;
    for (int index = 2; index < argc; ++index) {
        const std::wstring_view argument(argv[index]);
        if (argument == L"--scope" && ++index < argc) {
            const auto parsed = ParseScope(argv[index]);
            if (!parsed) { PrintUsage(); return 2; }
            scope = *parsed;
        } else if (argument == L"--source" && ++index < argc) {
            source_path = std::filesystem::path(argv[index]);
        } else { PrintUsage(); return 2; }
    }

    wchar_t executable_path[MAX_PATH]{};
    if (GetModuleFileNameW(nullptr, executable_path, MAX_PATH) == 0) {
        PrintFailure(L"GetModuleFileNameW", HRESULT_FROM_WIN32(GetLastError())); return 1;
    }
    const auto dll_path = source_path.value_or(DefaultSourcePath(executable_path));
    if (command == L"register" && !std::filesystem::is_regular_file(dll_path)) {
        std::wcerr << L"source DLL does not exist: " << dll_path.c_str() << L"\n"; return 2;
    }

    const HRESULT co_hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(co_hr) && co_hr != RPC_E_CHANGED_MODE) { PrintFailure(L"CoInitializeEx", co_hr); return 1; }
    const HRESULT mf_hr = MFStartup(MF_VERSION);
    if (FAILED(mf_hr)) { PrintFailure(L"MFStartup", mf_hr); if (SUCCEEDED(co_hr)) CoUninitialize(); return 1; }

    HRESULT hr = S_OK;
    std::wstring_view failure_operation = command;
    if (command == L"register") {
        failure_operation = L"COM registration";
        hr = WriteComRegistration(dll_path);
        if (SUCCEEDED(hr)) {
            IMFVirtualCamera* camera = nullptr;
            failure_operation = L"MFCreateVirtualCamera";
            hr = OpenCamera(scope, &camera);
            if (SUCCEEDED(hr)) {
                failure_operation = L"IMFVirtualCamera::Start";
                hr = camera->Start(nullptr);
                const bool started = SUCCEEDED(hr);
                RecordFailure(L"IMFVirtualCamera::Shutdown", camera->Shutdown(), hr,
                              failure_operation);
                camera->Release();
                if (!started) static_cast<void>(RemoveComRegistration());
            } else {
                static_cast<void>(RemoveComRegistration());
            }
        }
        if (SUCCEEDED(hr)) std::wcout << L"Registered " << kFriendlyName << L" Windows Virtual Camera.\n";
    } else if (command == L"unregister") {
        IMFVirtualCamera* camera = nullptr;
        failure_operation = L"MFCreateVirtualCamera";
        hr = OpenCamera(scope, &camera);
        if (SUCCEEDED(hr)) {
            failure_operation = L"IMFVirtualCamera::Stop";
            hr = camera->Stop();
            if (SUCCEEDED(hr)) {
                failure_operation = L"IMFVirtualCamera::Remove";
                hr = camera->Remove();
            }
            const bool removed = SUCCEEDED(hr);
            RecordFailure(L"IMFVirtualCamera::Shutdown", camera->Shutdown(), hr,
                          failure_operation);
            camera->Release();
            if (removed) {
                RecordFailure(L"COM registration removal", RemoveComRegistration(), hr,
                              failure_operation);
            }
        }
        if (SUCCEEDED(hr)) std::wcout << L"Removed " << kFriendlyName << L" Windows Virtual Camera.\n";
    } else if (command == L"diagnose-source") {
        failure_operation = L"diagnose-source";
        hr = DiagnoseSource(dll_path);
    } else { PrintUsage(); hr = E_INVALIDARG; }

    if (FAILED(hr)) PrintFailure(failure_operation, hr);
    MFShutdown();
    if (SUCCEEDED(co_hr)) CoUninitialize();
    return FAILED(hr) ? 1 : 0;
}
