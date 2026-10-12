# What Alt+Enter / Refactor This / Generate show at the positions of positions.txt, in the IDE the robot answers on ROBOT_PORT
# (the plugin's sandbox: 8583, Rider of start-rider.ps1: 8594). Real keyboard for Escape only (the window is activated first).
#   ROBOT_PORT=8583 IDE_PID=<pid> PROJECT=<project dir, forward slashes> OUT=<dump file> bash tools/ui-robot/compare/popups.sh [ACTIONS]
# ACTIONS: action ids, default "ShowIntentionActions RefactoringMenu Generate" (Rider: Refactorings.QuickListPopupAction for Refactor This).
export NO_PROXY=127.0.0.1 PYTHONIOENCODING=utf-8
R=$(git rev-parse --show-toplevel)
A=$R/tools/ui-robot/rider/analysis
. $A/esc.sh
ACTIONS=${1:-"ShowIntentionActions RefactoringMenu Generate"}
rj() {
  local file="$1"; shift
  local args=()
  for e in "$@"; do args+=(-e "$e"); done
  if [ ${#args[@]} -gt 0 ]; then sed "${args[@]}" "$file" > $R/build/compare/pop-tmp.js; else cp "$file" $R/build/compare/pop-tmp.js; fi
  timeout ${TMO:-150} python $R/tools/ui-robot/robot.py js $R/build/compare/pop-tmp.js 2>&1 | tr -d '\000-\010' | perl -0pe 's/^.*?@@@//s'
}
activate() { powershell -NoProfile -Command "\$ws = New-Object -ComObject WScript.Shell; [void]\$ws.AppActivate($IDE_PID)"; }
escape() { powershell -NoProfile -Command "\$ws = New-Object -ComObject WScript.Shell; [void]\$ws.AppActivate($IDE_PID); Start-Sleep -Milliseconds 300; \$ws.SendKeys('{ESC}')"; }
: > "$OUT"
grep -v '^#' $R/tools/ui-robot/compare/positions.txt | while IFS='|' read id file anchor off what; do
  [ -z "$id" ] && continue
  for action in $ACTIONS; do
    activate
    echo "######## $id $action — $what — «$anchor» ($file)" >> "$OUT"
    rj $A/popup.js "s|__FILE__|$PROJECT/$file|" "s|__AT__|$(esc "$anchor")|" "s|__OFF__|$off|" "s|__ACTION__||" "s|__KEYS__||" "s|__WAIT__|100|" "s|__LIMIT__|60|" "s|__CLOSE__|no|" | head -1 >> "$OUT"
    sleep ${SETTLE:-2}
    rj $A/poll.js "s|__ACTION__|$action|" > /dev/null
    rj $A/popup.js "s|__FILE__||" "s|__AT__||" "s|__OFF__|0|" "s|__ACTION__||" "s|__KEYS__||" "s|__WAIT__|100|" "s|__LIMIT__|60|" "s|__CLOSE__|no|" \
      | sed -e "s/&#39;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' -e 's/&amp;/\&/g' >> "$OUT"
    escape; sleep 1; escape; sleep 0.5
  done
done
