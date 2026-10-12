# Tab insertions with the real keyboard at the probes of tab_probes.txt, in the IDE on ROBOT_PORT (window of IDE_PID): a fresh line after
# the anchor, the keys, then the text around the caret (gray text, template, list); the file is put back from git and reloaded each time.
#   ROBOT_PORT=8583 IDE_PID=<pid> PROJECT=<dir> OUT=<dump> bash tools/ui-robot/compare/tab_probes.sh [ID-REGEX]
export NO_PROXY=127.0.0.1 PYTHONIOENCODING=utf-8
R=$(git rev-parse --show-toplevel)
C=$R/tools/ui-robot/compare
. $R/tools/ui-robot/rider/analysis/esc.sh
rj() {
  local file="$1"; shift
  local args=()
  for e in "$@"; do args+=(-e "$e"); done
  if [ ${#args[@]} -gt 0 ]; then sed "${args[@]}" "$file" > $R/build/compare/tab-tmp.js; else cp "$file" $R/build/compare/tab-tmp.js; fi
  timeout ${TMO:-120} python $R/tools/ui-robot/robot.py js $R/build/compare/tab-tmp.js 2>&1 | tr -d '\000-\010' | perl -0pe 's/^.*?@@@//s'
}
# t: steps type through the editor's typed action (layout-independent), k: / w: steps go to keys.ps1 (control keys, waits)
key() {
  local joined="${1//;;/$'\x1f'}"
  local parts
  IFS=$'\x1f' read -r -a parts <<< "$joined"
  for step in "${parts[@]}"; do
    case "$step" in
      t:*) rj $C/type_text.js "s|__TEXT__|$(esc "${step#t:}")|" "s|__DELAY__|60|" > /dev/null ;;
      # KEYS=awt: control keys through the AWT robot of the IDE (Rider ignores SendKeys' Tab), else through keys.ps1
      k:*) if [ "${KEYS:-sendkeys}" = awt ]; then local k="${step#k:}"; k="${k#\{}"; k="${k%\}}"; [ "$k" = ESC ] && k=ESCAPE; rj $C/press_key.js "s|__KEY__|$k|g" > /dev/null; else powershell -NoProfile -File "$(cygpath -w $C/keys.ps1)" -Id $IDE_PID -Steps "$step"; fi ;;
      *) powershell -NoProfile -File "$(cygpath -w $C/keys.ps1)" -Id $IDE_PID -Steps "$step" ;;
    esac
  done
}
: > "$OUT"
grep -v '^#' $C/tab_probes.txt | grep -E "^(${1:-.*})\|" | while IFS='|' read id file anchor extra steps what; do
  [ -z "$id" ] && continue
  git -C "$PROJECT" checkout -q -- "$file"
  echo "######## $id — $what — keys: $steps" >> "$OUT"
  rj $C/new_line.js "s|__FILE__|$PROJECT/$file|" "s|__AT__|$(esc "$anchor")|" "s|__EXTRA__|$extra|" > /dev/null
  sleep ${SETTLE:-2}
  key "$steps"
  rj $C/around_caret.js "s|__BEFORE__|3|" "s|__AFTER__|10|" >> "$OUT"
  # gray text shown: what Tab writes
  if tail -5 "$OUT" | grep -q "^gray: \["; then
    key "k:{TAB};;w:1200"
    echo "-- after Tab on the gray text:" >> "$OUT"
    rj $C/around_caret.js "s|__BEFORE__|3|" "s|__AFTER__|10|" >> "$OUT"
  fi
  key "k:{ESC};;w:200;;k:{ESC}"
  git -C "$PROJECT" checkout -q -- "$file"
  rj $C/new_line.js "s|__FILE__|$PROJECT/$file|" "s|__AT__|@@no-such-anchor@@|" "s|__EXTRA__|0|" > /dev/null
  sleep 1
done
