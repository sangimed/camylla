#pragma once

#include <mfidl.h>
#include <wrl.h>

namespace camylla::virtual_camera::win {

inline constexpr wchar_t kSourceClsidText[] = L"{CA9F0B23-C7E0-42C7-94B4-2D6A5FAD544D}";
extern const CLSID kSourceClsid;

HRESULT CreateActivation(REFIID iid, void** object) noexcept;

}  // namespace camylla::virtual_camera::win
