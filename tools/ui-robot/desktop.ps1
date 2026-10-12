# Real mouse, keyboard and screen on the Windows desktop (nothing to install: .NET + user32), for live checks the robot API cannot do:
# hovers (documentation / inlay popups), typing as a person types, screenshots of what the user would see. The desktop is busy meanwhile.
#   desktop.ps1 shot <out.png> [<window title substring>]   the whole virtual screen, or the window whose title contains the text
#   desktop.ps1 window <title substring>                     prints "x y w h" of that window (and brings it to the front)
#   desktop.ps1 move <x> <y>                                  pointer to screen coordinates (hover: move, then move 2 px, wait, shot)
#   desktop.ps1 click <x> <y> [right|double]
#   desktop.ps1 drag <x1> <y1> <x2> <y2>
#   desktop.ps1 wheel <x> <y> <notches>                       negative = down
#   desktop.ps1 key <keys>                                    SendKeys syntax: ^(space) {ESC} %{ENTER} +{F6} {F2} ^+t
#   desktop.ps1 type <text>                                   character by character, 30 ms apart (SendKeys specials are escaped)
#   desktop.ps1 pos                                           the pointer position
# Seen live (RDP session): injected moves place the pointer but Swing shows no tooltip and no quick-doc on hover for them — hover through the
# IDE's own AWT robot instead (scripts/hover.js, quickdoc.js); clicks, typing and screenshots from here do work.
# Coordinates are physical pixels of the virtual screen (DPI-aware process). Pair with robot.py: component bounds from `find` are in the
# IDE window's logical coordinates — use `window` for the origin and the scale printed there.
param([Parameter(Mandatory = $true)][string]$Command, [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -Namespace Desk -Name Native -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
[DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
[DllImport("user32.dll")] public static extern void mouse_event(uint flags, int dx, int dy, int data, UIntPtr extra);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
[DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
[DllImport("user32.dll")] public static extern int GetSystemMetrics(int i);
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
[StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
'@
[Desk.Native]::SetProcessDPIAware() | Out-Null
function Window([string]$title) {
    $p = Get-Process | Where-Object { $_.MainWindowTitle -and $_.MainWindowTitle -like "*$title*" } | Select-Object -First 1
    if ($null -eq $p) { throw "no window with '$title' in its title" }
    if ([Desk.Native]::IsIconic($p.MainWindowHandle)) { [Desk.Native]::ShowWindow($p.MainWindowHandle, 9) | Out-Null }
    [Desk.Native]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
    $r = New-Object Desk.Native+RECT
    [Desk.Native]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
    return @{ Rect = $r; Process = $p }
}
function Screen() {
    $x = [Desk.Native]::GetSystemMetrics(76); $y = [Desk.Native]::GetSystemMetrics(77)
    $w = [Desk.Native]::GetSystemMetrics(78); $h = [Desk.Native]::GetSystemMetrics(79)
    return New-Object System.Drawing.Rectangle($x, $y, $w, $h)
}
function Shot([string]$out, [System.Drawing.Rectangle]$area) {
    $bmp = New-Object System.Drawing.Bitmap($area.Width, $area.Height)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($area.Location, [System.Drawing.Point]::Empty, $area.Size)
    $g.Dispose()
    $full = [System.IO.Path]::GetFullPath($out)
    $bmp.Save($full, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    "$full $($area.Width)x$($area.Height) at $($area.X),$($area.Y)"
}
function MoveTo([int]$x, [int]$y) { [Desk.Native]::SetCursorPos($x, $y) | Out-Null; Start-Sleep -Milliseconds 60 }
function Button([string]$kind, [int]$times) {
    $down = 0x0002; $up = 0x0004
    if ($kind -eq "right") { $down = 0x0008; $up = 0x0010 }
    for ($i = 0; $i -lt $times; $i++) { [Desk.Native]::mouse_event($down, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 40; [Desk.Native]::mouse_event($up, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 80 }
}
switch ($Command) {
    "shot" {
        $area = if ($Rest.Count -ge 2) { $r = (Window $Rest[1]).Rect; New-Object System.Drawing.Rectangle($r.Left, $r.Top, ($r.Right - $r.Left), ($r.Bottom - $r.Top)) } else { Screen }
        Shot $Rest[0] $area
    }
    "window" { $w = Window $Rest[0]; $r = $w.Rect; "$($r.Left) $($r.Top) $($r.Right - $r.Left) $($r.Bottom - $r.Top) pid=$($w.Process.Id) title=$($w.Process.MainWindowTitle)" }
    "move" { MoveTo $Rest[0] $Rest[1]; "moved" }
    "hover" { # approach in real input steps (MOUSEEVENTF_MOVE), then rest: a teleport alone does not count as a hover for some popups
        MoveTo ($Rest[0] - 40) ($Rest[1] - 20)
        for ($i = 0; $i -lt 20; $i++) { [Desk.Native]::mouse_event(0x0001, 2, 1, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 25 }
        MoveTo $Rest[0] $Rest[1]; [Desk.Native]::mouse_event(0x0001, 1, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 100; [Desk.Native]::mouse_event(0x0001, -1, 0, 0, [UIntPtr]::Zero)
        $ms = if ($Rest.Count -ge 3) { [int]$Rest[2] } else { 2500 }; Start-Sleep -Milliseconds $ms; "hovered" }
    "click" { MoveTo $Rest[0] $Rest[1]; $kind = if ($Rest.Count -ge 3) { $Rest[2] } else { "left" }; Button $kind $(if ($kind -eq "double") { 2 } else { 1 }); "clicked" }
    "drag" { MoveTo $Rest[0] $Rest[1]; [Desk.Native]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 120
             $steps = 12; for ($i = 1; $i -le $steps; $i++) { MoveTo ([int]($Rest[0] + ($Rest[2] - $Rest[0]) * $i / $steps)) ([int]($Rest[1] + ($Rest[3] - $Rest[1]) * $i / $steps)) }
             [Desk.Native]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero); "dragged" }
    "wheel" { MoveTo $Rest[0] $Rest[1]; [Desk.Native]::mouse_event(0x0800, 0, 0, ([int]$Rest[2] * 120), [UIntPtr]::Zero); "scrolled" }
    "key" { [System.Windows.Forms.SendKeys]::SendWait($Rest -join " "); "sent" }
    "type" { foreach ($ch in ($Rest -join " ").ToCharArray()) { $s = [string]$ch; if ("+^%~(){}[]".Contains($s)) { $s = "{$s}" }; [System.Windows.Forms.SendKeys]::SendWait($s); Start-Sleep -Milliseconds 30 }; "typed" }
    "pos" { $p = New-Object Desk.Native+POINT; [Desk.Native]::GetCursorPos([ref]$p) | Out-Null; "$($p.X) $($p.Y)" }
    default { throw "unknown command $Command" }
}
