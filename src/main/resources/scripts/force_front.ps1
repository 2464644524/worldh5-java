param(
  [Parameter(Mandatory=$true)]
  [string]$ProfileDir
)

$ErrorActionPreference = "SilentlyContinue"

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class Win32Foreground {
  [DllImport("user32.dll")]
  public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")]
  public static extern bool BringWindowToTop(IntPtr hWnd);
  [DllImport("user32.dll")]
  public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")]
  public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")]
  public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
  [DllImport("user32.dll")]
  public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
}
"@

$handles = New-Object System.Collections.ArrayList
Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" | ForEach-Object {
  if ($_.CommandLine -and $_.CommandLine.Contains($ProfileDir)) {
    $process = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
    if ($process -and $process.MainWindowHandle -ne [IntPtr]::Zero) {
      [void]$handles.Add($process.MainWindowHandle)
    }
  }
}

$foregroundHandle = [Win32Foreground]::GetForegroundWindow()
$foregroundProcessId = 0
$foregroundThreadId = [Win32Foreground]::GetWindowThreadProcessId(
  $foregroundHandle, [ref]$foregroundProcessId)

foreach ($handle in ($handles | Select-Object -Unique)) {
  $targetProcessId = 0
  $targetThreadId = [Win32Foreground]::GetWindowThreadProcessId(
    $handle, [ref]$targetProcessId)

  [Win32Foreground]::ShowWindowAsync($handle, 9) | Out-Null
  [Win32Foreground]::AttachThreadInput($targetThreadId, $foregroundThreadId, $true) | Out-Null
  [Win32Foreground]::BringWindowToTop($handle) | Out-Null
  [Win32Foreground]::SetForegroundWindow($handle) | Out-Null
  [Win32Foreground]::AttachThreadInput($targetThreadId, $foregroundThreadId, $false) | Out-Null
}