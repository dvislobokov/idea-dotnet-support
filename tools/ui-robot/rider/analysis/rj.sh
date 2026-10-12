# Helpers for the Rider analysis (docs/rider-analysis/README.md). Source from Git Bash in the repository root after start-rider.ps1:
#   export RIDER_PID=<pid of rider64.exe started by start-rider.ps1>; . tools/ui-robot/rider/analysis/rj.sh
# The probe file RiderAnalysis.cs must be copied into build/ui-robot/rider-playground/Console/Editor/ first (and removed afterwards).
export ROBOT_PORT=8594 NO_PROXY=127.0.0.1 PYTHONIOENCODING=utf-8
R=$(git rev-parse --show-toplevel)
A=$R/tools/ui-robot/rider/analysis
# rj FILE [sed-expr...]: runs a script with placeholders filled in, prints what it returns (scripts prefix their result with @@@)
rj() {
  local file="$1"; shift
  local args=()
  for e in "$@"; do args+=(-e "$e"); done
  if [ ${#args[@]} -gt 0 ]; then sed "${args[@]}" "$file" > $R/build/ra-tmp.js; else cp "$file" $R/build/ra-tmp.js; fi
  timeout ${TMO:-150} python $R/tools/ui-robot/robot.py js $R/build/ra-tmp.js 2>&1 | tr -d '\000-\010' | sed '1s/^.*@@@//'
}
# Popups and completion need the Rider window to be the foreground window (Windows refuses focusProjectWindow otherwise)
activate() { powershell -NoProfile -Command "\$ws = New-Object -ComObject WScript.Shell; [void]\$ws.AppActivate($RIDER_PID)"; }
# Escape closes Rider's Alt+Enter-like popups cleanly; closing them by moving the caret froze the EDT once (modal popup cookie)
escape() { powershell -NoProfile -Command "\$ws = New-Object -ComObject WScript.Shell; [void]\$ws.AppActivate($RIDER_PID); Start-Sleep -Milliseconds 300; \$ws.SendKeys('{ESC}')"; }
P=$(cd $R/build/ui-robot/rider-playground && pwd -W)
. $A/esc.sh
# comp MARK TYPE TYPED KIND [PICK] [LIMIT] [FILE]: completion probe (complete2.js)
comp() {
  local f="${7:-$P/Console/Editor/RiderAnalysis.cs}"
  rj $A/complete2.js "s|__FILE__|$f|" "s|__MARK__|$(esc "$1")|" "s|__TYPE__|$(esc "$2")|" "s|__TYPED__|$(esc "$3")|" "s|__KIND__|$4|" "s|__PICK__|$(esc "${5:-}")|" "s|__LIMIT__|${6:-15}|" "s|__SYNC__|${SYNC:-1200}|" "s|__WAIT__|${WAIT:-4000}|" "s|__CHAR__|${CHAR:-n}|" "s|__AFTER__|${AFTER:-1500}|" "s|__SHOW__|${SHOW:-4}|" "s|__TIME__|${TIME:-1}|" "s|__KEEP__|${KEEP:-no}|"
}
# pop FILE ANCHOR OFFSET ACTION [SHOTNAME]: caret at ANCHOR+OFFSET, action (tryToExecute), dump the popup list, screenshot, Escape
pop() {
  activate
  echo "######## $4 at «$2» ($1)"
  rj $A/popup.js "s|__FILE__|$P/$1|" "s|__AT__|$(esc "$2")|" "s|__OFF__|$3|" "s|__ACTION__||" "s|__KEYS__||" "s|__WAIT__|100|" "s|__LIMIT__|80|" "s|__CLOSE__|no|"
  sleep 2
  rj $A/poll.js "s|__ACTION__|$4|" > /dev/null
  rj $A/popup.js "s|__FILE__||" "s|__AT__||" "s|__OFF__|0|" "s|__ACTION__||" "s|__KEYS__||" "s|__WAIT__|100|" "s|__LIMIT__|80|" "s|__CLOSE__|no|" | sed -e "s/&#39;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' -e 's/&amp;/\&/g'
  if [ -n "$5" ]; then python $R/tools/ui-robot/robot.py shot "$R/docs/rider-analysis/img/$5" "//div[@class='HeavyWeightWindow'][.//div[@class='MyList']]" >/dev/null 2>&1; fi
  escape; sleep 1
}
