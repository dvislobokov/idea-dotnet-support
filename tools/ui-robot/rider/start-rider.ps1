# Starts Rider as a reference IDE for the UI robot (what it suggests is the target of the plugin's completion and gray text), isolated
# from the user's own Rider: a copy of its config (license included, the password store `c.kdbx` / `c.pwd` left out), its own system,
# plugins (only robot-server-plugin, taken from the plugin's UI-test sandbox) and logs under $Root. The user's Rider is not touched.
#   powershell -File tools/ui-robot/rider/start-rider.ps1 -Rider "C:\Program Files\JetBrains\JetBrains Rider 2025.1.3" -Config Rider2025.1
# Then: ROBOT_PORT=8594 python tools/ui-robot/robot.py wait; scripts — tools/ui-robot/scripts/rider_complete.js (README of the robot).
param(
    [string]$Rider = "C:\Program Files\JetBrains\JetBrains Rider 2025.1.3",
    [string]$Config = "Rider2025.1",
    [string]$Root = "$env:USERPROFILE\rider-robot",
    [int]$Port = 8594,
    [string]$Solution = "$PSScriptRoot\..\..\..\build\ui-robot\rider-playground\DebugPlayground.sln"
)
$ErrorActionPreference = "Stop"
$robot = Get-ChildItem "$PSScriptRoot\..\..\..\.intellijPlatform\sandbox" -Recurse -Directory -Filter robot-server-plugin | Select-Object -First 1
if ($null -eq $robot) { throw "robot-server-plugin not found: run ./gradlew.bat runIdeForUiTests once" }
foreach ($dir in "config", "system", "plugins", "log") { New-Item -ItemType Directory -Force "$Root\$dir" | Out-Null }
if (-not (Test-Path "$Root\config\options")) {
    Get-ChildItem "$env:APPDATA\JetBrains\$Config" -Exclude c.kdbx, c.pwd, plugins | Copy-Item -Destination "$Root\config" -Recurse -Force
}
Copy-Item $robot.FullName "$Root\plugins" -Recurse -Force
$r = $Root -replace '\\', '/'
"idea.config.path=$r/config`nidea.system.path=$r/system`nidea.plugins.path=$r/plugins`nidea.log.path=$r/log" | Out-File -Encoding ascii "$Root\rider.properties"
$options = Get-Content "$Rider\bin\rider64.exe.vmoptions"
$options += "-Drobot-server.port=$Port", "-Djb.privacy.policy.text=<!--999.999-->", "-Djb.consents.confirmation.enabled=false",
    "-Dide.show.tips.on.startup.default.value=false", "-Didea.trust.all.projects=true"
$options | Out-File -Encoding ascii "$Root\rider.vmoptions"
$env:RIDER_PROPERTIES = "$Root\rider.properties"
$env:RIDER_VM_OPTIONS = "$Root\rider.vmoptions"
Start-Process -FilePath "$Rider\bin\rider64.exe" -ArgumentList "`"$((Resolve-Path $Solution).Path)`""
"Rider started, robot on 127.0.0.1:$Port"
