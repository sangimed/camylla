#pragma once

#include <windows.h>
#include <filesystem>

HRESULT DiagnoseSource(const std::filesystem::path& dll_path);
