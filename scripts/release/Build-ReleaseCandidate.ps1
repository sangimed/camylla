[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$')]
    [string]$Version,
    [Parameter(Mandatory)]
    [ValidateRange(1, 2100000000)]
    [int]$VersionCode,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\artifacts\release-candidate'),
    [string]$AndroidKeystorePath,
    [string]$AndroidKeyAlias
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

$androidSigningValues = @($AndroidKeystorePath, $AndroidKeyAlias, $env:CAMYLLA_ANDROID_KEYSTORE_PASSWORD, $env:CAMYLLA_ANDROID_KEY_PASSWORD)
$signAndroid = $androidSigningValues | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
if ($signAndroid.Count -ne 0 -and $signAndroid.Count -ne $androidSigningValues.Count) {
    throw 'Android signing requires a keystore path, key alias, keystore password, and key password.'
}
if ($signAndroid.Count -eq $androidSigningValues.Count -and -not (Test-Path -LiteralPath $AndroidKeystorePath -PathType Leaf)) {
    throw "Android signing keystore is missing: $AndroidKeystorePath"
}

Invoke-Checked 'cmake' @('-S', $repo, '-B', $cmakeBuild, '-G', 'Visual Studio 17 2022', '-A', 'x64') $repo
Invoke-Checked 'cmake' @('--build', $cmakeBuild, '--config', 'Release', '--parallel') $repo
Invoke-Checked 'ctest' @('--test-dir', $cmakeBuild, '-C', 'Release', '--output-on-failure') $repo
Invoke-Checked (Join-Path $repo 'src\android\gradlew.bat') @(':app:testDebugUnitTest', ':app:assembleRelease', "-PcamyllaVersion=$Version", "-PcamyllaVersionCode=$VersionCode", '--no-daemon') (Join-Path $repo 'src\android')

$desktop = Join-Path $stage 'windows-x64-portable'
Invoke-Checked 'dotnet' @('publish', 'src/windows/desktop/Camylla.Desktop.csproj', '-c', 'Release', '-r', 'win-x64', '--self-contained', 'true', "-p:CamyllaVersion=$Version", '-o', $desktop) $repo

# WinUI generates the application's XAML resource index beside the build output,
# but dotnet publish does not copy it for an unpackaged application. Without it,
# the desktop executable exits while loading MainWindow.xaml.
$desktopPri = Join-Path $repo 'src\windows\desktop\bin\Release\net10.0-windows10.0.19041.0\win-x64\Camylla.Desktop.pri'
if (-not (Test-Path -LiteralPath $desktopPri -PathType Leaf)) { throw "Required WinUI resource index is missing: $desktopPri" }
Copy-Item -LiteralPath $desktopPri -Destination (Join-Path $desktop 'Camylla.Desktop.pri')

$nativeFiles = @(
    @{ Source = Join-Path $cmakeBuild 'src\windows\receiver\native\Release\camylla_mf_decoder.dll'; Destination = 'camylla_mf_decoder.dll' },
    @{ Source = Join-Path $cmakeBuild 'src\windows\virtual-camera\Release\camylla_virtual_camera_source.dll'; Destination = 'camylla_virtual_camera_source.dll' },
    @{ Source = Join-Path $cmakeBuild 'src\windows\virtual-camera\Release\camylla_virtual_camera_ctl.exe'; Destination = 'camylla_virtual_camera_ctl.exe' }
)
foreach ($file in $nativeFiles) {
    if (-not (Test-Path -LiteralPath $file.Source -PathType Leaf)) { throw "Required release file is missing: $($file.Source)" }
    Copy-Item -LiteralPath $file.Source -Destination (Join-Path $desktop $file.Destination)
}

Invoke-Checked (Join-Path $PSScriptRoot 'Test-ReleaseCandidate.ps1') @('-DesktopDirectory', $desktop) $repo

@"
# Camylla portable build

This folder is an unsigned portable alpha build. Windows may display a
SmartScreen warning because the binaries are intentionally unsigned.

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
$apkName = if ($signAndroid.Count -eq $androidSigningValues.Count) { "Camylla-$Version-android.apk" } else { "Camylla-$Version-android-unsigned.apk" }
$apkDestination = Join-Path $output $apkName
if ($signAndroid.Count -eq $androidSigningValues.Count) {
    $buildTools = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Directory | Sort-Object Name -Descending
    $apksigner = $buildTools | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $apksigner) { throw 'Android SDK apksigner was not found.' }
    Invoke-Checked $apksigner @('sign', '--v4-signing-enabled', 'false', '--ks', $AndroidKeystorePath, '--ks-key-alias', $AndroidKeyAlias, '--ks-pass', 'env:CAMYLLA_ANDROID_KEYSTORE_PASSWORD', '--key-pass', 'env:CAMYLLA_ANDROID_KEY_PASSWORD', '--out', $apkDestination, $apk) $repo
    Invoke-Checked $apksigner @('verify', '--verbose', $apkDestination) $repo
}
else {
    Copy-Item -LiteralPath $apk -Destination $apkDestination
}

$zip = Join-Path $output "Camylla-$Version-windows-x64-portable-unsigned.zip"
Compress-Archive -LiteralPath $desktop -DestinationPath $zip -CompressionLevel Optimal

$installerScript = Join-Path $repo 'scripts\release\CamyllaInstaller.iss'
if (-not (Test-Path -LiteralPath $installerScript -PathType Leaf)) { throw "Installer script is missing: $installerScript" }
$iscc = Get-Command 'ISCC.exe' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source
if (-not $iscc) { throw 'Inno Setup Compiler (ISCC.exe) is required to build the Windows installer.' }
$setup = Join-Path $output "Camylla-$Version-windows-x64-setup-unsigned.exe"
Invoke-Checked $iscc @("/DMyAppVersion=$Version", "/DMySourceDir=$desktop", "/DMyOutputDir=$output", $installerScript) $repo
if (-not (Test-Path -LiteralPath $setup -PathType Leaf)) { throw "Required Windows installer is missing: $setup" }

$downloadFiles = @($setup, $zip, $apkDestination)
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
    signed = ($signAndroid.Count -eq $androidSigningValues.Count)
    files = $files
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'candidate-manifest.json') -Encoding utf8NoBOM
Get-FileHash -LiteralPath $downloadFiles -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($_.Path))" } |
    Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS') -Encoding ascii

Write-Host "Release candidate created in $output"
