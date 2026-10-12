# Real keyboard into an IDE window (the user said they do not touch the keyboard): activates the window of process -Id and plays the
# steps of -Steps, separated by ";;": "t:text" types text (every SendKeys special character escaped), "k:{TAB}" sends a raw SendKeys
# key, "w:800" waits milliseconds. Typing goes character by character, 40 ms apart, as a person types (the IDE's auto-popup sees each).
param([int]$Id, [string]$Steps)
$ws = New-Object -ComObject WScript.Shell
[void]$ws.AppActivate($Id)
Start-Sleep -Milliseconds 400
foreach ($step in ($Steps -split ';;')) {
    if ($step.StartsWith('t:')) {
        foreach ($c in $step.Substring(2).ToCharArray()) {
            $s = [string]$c
            if ('{}()[]+^%~'.Contains($s)) { $s = '{' + $s + '}' }
            $ws.SendKeys($s)
            Start-Sleep -Milliseconds 40
        }
    } elseif ($step.StartsWith('k:')) { $ws.SendKeys($step.Substring(2)) }
    elseif ($step.StartsWith('w:')) { Start-Sleep -Milliseconds ([int]$step.Substring(2)) }
}
