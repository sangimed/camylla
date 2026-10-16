[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$DesktopDirectory
)

$ErrorActionPreference = 'Stop'
$desktop = [System.IO.Path]::GetFullPath($DesktopDirectory)

foreach ($name in @(
    'Camylla.Desktop.exe',
    'Camylla.Desktop.pri',
    'camylla_mf_decoder.dll',
    'camylla_virtual_camera_ctl.exe',
    'camylla_virtual_camera_source.dll'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $desktop $name) -PathType Leaf)) {
        throw "Release desktop layout is missing required file: $name"
    }
}

Write-Host 'Release desktop layout contains the executable, WinUI resources, and native components.'
