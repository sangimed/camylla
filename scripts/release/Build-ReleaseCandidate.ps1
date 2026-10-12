[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$')]
    [string]$Version,
    [Parameter(Mandatory)]
    [ValidateRange(1, 2100000000)]
    [int]$VersionCode,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\artifacts\release-candidate')
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = [System.IO.Path]::GetFullPath($OutputDirectory, $repo)
$repoPrefix = $repo.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($repoPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must be inside the repository.'
}
$stage = Join-Path $output 'stage'
$cmakeBuild = Join-Path $repo 'build\release-candidate'

Remove-Item -LiteralPath $output -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $stage | Out-Null

function Invoke-Checked([string]$File, [string[]]$Arguments, [string]$WorkingDirectory) {
    Push-Location $WorkingDirectory
    try {
        & $File @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$File failed with exit code $LASTEXITCODE" }
    }
    finally { Pop-Location }
}

Invoke-Checked 'cmake' @('-S', $repo, '-B', $cmakeBuild, '-G', 'Visual Studio 17 2022', '-A', 'x64') $repo
Invoke-Checked 'cmake' @('--build', $cmakeBuild, '--config', 'Release', '--parallel') $repo
Invoke-Checked 'ctest' @('--test-dir', $cmakeBuild, '-C', 'Release', '--output-on-failure') $repo
Invoke-Checked (Join-Path $repo 'src\android\gradlew.bat') @(':app:testDebugUnitTest', ':app:assembleRelease', "-PcamyllaVersion=$Version", "-PcamyllaVersionCode=$VersionCode", '--no-daemon') (Join-Path $repo 'src\android')

$desktop = Join-Path $stage 'windows-x64-portable'
Invoke-Checked 'dotnet' @('publish', 'src/windows/desktop/Camylla.Desktop.csproj', '-c', 'Release', '-r', 'win-x64', '--self-contained', 'true', "-p:CamyllaVersion=$Version", '-o', $desktop) $repo

$nativeFiles = @(
    @{ Source = Join-Path $cmakeBuild 'src\windows\receiver\native\Release\camylla_mf_decoder.dll'; Destination = 'camylla_mf_decoder.dll' },
    @{ Source = Join-Path $cmakeBuild 'src\windows\virtual-camera\Release\camylla_virtual_camera_source.dll'; Destination = 'camylla_virtual_camera_source.dll' },
    @{ Source = Join-Path $cmakeBuild 'src\windows\virtual-camera\Release\camylla_virtual_camera_ctl.exe'; Destination = 'camylla_virtual_camera_ctl.exe' }
)
foreach ($file in $nativeFiles) {
    if (-not (Test-Path -LiteralPath $file.Source -PathType Leaf)) { throw "Required release file is missing: $($file.Source)" }
    Copy-Item -LiteralPath $file.Source -Destination (Join-Path $desktop $file.Destination)
}

@"
# Camylla portable build

This folder is an unsigned release candidate. It is not a supported public
release until the release workflow has signed and verified every binary.

Run Camylla.Desktop.exe from this folder. Do not move or delete this folder
while its virtual camera is registered. The virtual camera is optional but its
registration is a machine-wide COM operation: open an elevated PowerShell in
this folder and run:

    .\camylla_virtual_camera_ctl.exe register --scope current-user

Remove the camera before deleting or moving this folder:

    .\camylla_virtual_camera_ctl.exe unregister --scope current-user
"@ | Set-Content -LiteralPath (Join-Path $desktop 'PORTABLE.md') -Encoding utf8NoBOM

$apk = Join-Path $repo 'src\android\app\build\outputs\apk\release\app-release-unsigned.apk'
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Required Android release APK is missing: $apk" }
$apkDestination = Join-Path $output "Camylla-$Version-android-unsigned.apk"
Copy-Item -LiteralPath $apk -Destination $apkDestination

$zip = Join-Path $output "Camylla-$Version-windows-x64-portable-unsigned.zip"
Compress-Archive -LiteralPath $desktop -DestinationPath $zip -CompressionLevel Optimal

$downloadFiles = @($zip, $apkDestination)
$files = $downloadFiles | ForEach-Object {
    [ordered]@{
        path = [System.IO.Path]::GetFileName($_)
        sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $_).Length
    }
}
$manifest = [ordered]@{
    version = $Version
    version_code = $VersionCode
    commit = (& git -C $repo rev-parse HEAD).Trim()
    signed = $false
    files = $files
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'candidate-manifest.json') -Encoding utf8NoBOM
Get-FileHash -LiteralPath $downloadFiles -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($_.Path))" } |
    Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS') -Encoding ascii

Write-Host "Unsigned release candidate created in $output"
