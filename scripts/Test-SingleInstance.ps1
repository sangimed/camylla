param(
    [string]$ExecutablePath = (Join-Path $PSScriptRoot '..\src\windows\desktop\bin\x64\Debug\net10.0-windows10.0.19041.0\win-x64\Camylla.Desktop.exe')
)

$ErrorActionPreference = 'Stop'
$executable = (Resolve-Path -LiteralPath $ExecutablePath).Path
$portOwners = Get-NetTCPConnection -State Listen -LocalPort 58413,58414 -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique
if ($portOwners) {
    throw "Close existing Camylla listener processes before running this integration test (PIDs: $($portOwners -join ', '))."
}

$primary = $null
$secondary = $null
try {
    $primary = Start-Process -FilePath $executable -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $primary.Refresh()
        if ($primary.HasExited) { throw "Primary Camylla process exited with code $($primary.ExitCode)." }
        $ownedPorts = Get-NetTCPConnection -State Listen -LocalPort 58413,58414 -ErrorAction SilentlyContinue |
            Where-Object OwningProcess -eq $primary.Id |
            Select-Object -ExpandProperty LocalPort -Unique
        if (@($ownedPorts).Count -eq 2) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    if (@($ownedPorts).Count -ne 2) { throw 'Primary Camylla process did not acquire both listener ports.' }
    $primaryWindow = [IntPtr]$primary.MainWindowHandle
    if ($primaryWindow -eq [IntPtr]::Zero) { throw 'Primary Camylla window was not available to test tray-style reactivation.' }
    if (-not ('CamyllaSingleInstanceTestNativeMethods' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CamyllaSingleInstanceTestNativeMethods
{
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr window, int command);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
}
'@
    }
    [void][CamyllaSingleInstanceTestNativeMethods]::ShowWindow($primaryWindow, 0)
    if ([CamyllaSingleInstanceTestNativeMethods]::IsWindowVisible($primaryWindow)) {
        throw 'Primary Camylla window could not be hidden for the tray-reactivation check.'
    }

    $secondary = Start-Process -FilePath $executable -PassThru
    if (-not $secondary.WaitForExit(10000)) {
        throw 'Second launch remained alive instead of redirecting activation to the existing process.'
    }

    $primary.Refresh()
    $activationDeadline = [DateTime]::UtcNow.AddSeconds(3)
    while (-not [CamyllaSingleInstanceTestNativeMethods]::IsWindowVisible($primaryWindow) -and
        [DateTime]::UtcNow -lt $activationDeadline) {
        Start-Sleep -Milliseconds 100
    }
    $listenerOwners = Get-NetTCPConnection -State Listen -LocalPort 58413,58414 -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    if ($primary.HasExited -or -not [CamyllaSingleInstanceTestNativeMethods]::IsWindowVisible($primaryWindow) -or
        @($listenerOwners).Count -ne 1 -or $listenerOwners[0] -ne $primary.Id) {
        throw 'The first Camylla process did not retain exclusive ownership of both listener ports.'
    }

    Write-Output 'PASS: a second Camylla launch redirected to the existing process; one receiver retained both ports.'
}
finally {
    if ($secondary) {
        $secondary.Refresh()
        if (-not $secondary.HasExited) {
            [void]$secondary.CloseMainWindow()
            if (-not $secondary.WaitForExit(10000)) {
                Write-Warning "Test process $($secondary.Id) is still running; close its window before continuing."
            }
        }
    }
    if ($primary) {
        $primary.Refresh()
        if (-not $primary.HasExited) {
            if ($primaryWindow -ne [IntPtr]::Zero -and
                ('CamyllaSingleInstanceTestNativeMethods' -as [type])) {
                [void][CamyllaSingleInstanceTestNativeMethods]::ShowWindow($primaryWindow, 5)
            }
            [void]$primary.CloseMainWindow()
            if (-not $primary.WaitForExit(10000)) {
                Write-Warning "Test process $($primary.Id) is still running; close its window before continuing."
            }
        }
    }
}
