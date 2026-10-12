# Executes the context actions / refactorings of refactor_probes.txt in the IDE on ROBOT_PORT (window of IDE_PID) and records the
# diff they make (all files of the project: rename touches several): caret at the anchor, the action, the row chosen by its text, Enter
# with the real keyboard, extra keys, documents saved, `git diff`, the project put back from git and the files reloaded.
#   ROBOT_PORT=8583 IDE_PID=<pid> PROJECT=<dir> OUT=<dump> bash tools/ui-robot/compare/refactor_probes.sh [ID-REGEX]
export NO_PROXY=127.0.0.1 PYTHONIOENCODING=utf-8
R=$(git rev-parse --show-toplevel)
C=$R/tools/ui-robot/compare
A=$R/tools/ui-robot/rider/analysis
. $A/esc.sh
rj() {
  local file="$1"; shift
  local args=()
  for e in "$@"; do args+=(-e "$e"); done
  if [ ${#args[@]} -gt 0 ]; then sed "${args[@]}" "$file" > $R/build/compare/ref-tmp.js; else cp "$file" $R/build/compare/ref-tmp.js; fi
  timeout ${TMO:-150} python $R/tools/ui-robot/robot.py js $R/build/compare/ref-tmp.js 2>&1 | tr -d '\000-\010' | perl -0pe 's/^.*?@@@//s'
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
cat > $R/build/compare/save-all.js <<'EOF'
com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
} }), com.intellij.openapi.application.ModalityState.any())
"@@@saved"
EOF
cat > $R/build/compare/reload-all.js <<'EOF'
com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    com.intellij.openapi.vfs.VirtualFileManager.getInstance().syncRefresh()
    var docs = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getUnsavedDocuments()
    for (var i = 0; i < docs.length; i++) com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().reloadFromDisk(docs[i])
} }), com.intellij.openapi.application.ModalityState.any())
"@@@reloaded"
EOF
: > "$OUT"
grep -v '^#' $C/refactor_probes.txt | grep -E "^(${1:-.*})\|" | while IFS='|' read id file anchor off action row extra what; do
  [ -z "$id" ] && continue
  git -C "$PROJECT" reset -q --hard
  rj $R/build/compare/reload-all.js > /dev/null
  echo "######## $id — $what — $action «$row»" >> "$OUT"
  rj $A/popup.js "s|__FILE__|$PROJECT/$file|" "s|__AT__|$(esc "$anchor")|" "s|__OFF__|$off|" "s|__ACTION__||" "s|__KEYS__||" "s|__WAIT__|100|" "s|__LIMIT__|60|" "s|__CLOSE__|no|" | head -1 >> "$OUT"
  sleep ${SETTLE:-3}
  rj $A/poll.js "s|__ACTION__|$action|" > /dev/null
  if [ -n "$row" ]; then
    sel=$(rj $C/select_row.js "s|__TEXT__|$(esc "$row")|")
    echo "$sel" >> "$OUT"
    if echo "$sel" | grep -q "^selected"; then key "k:{ENTER}"; fi
  fi
  [ -n "$extra" ] && key "$extra"
  sleep 3
  # a dialog left open (Rider's rename, a conflict): its texts, then Escape
  rj $A/popup.js "s|__FILE__||" "s|__AT__||" "s|__OFF__|0|" "s|__ACTION__||" "s|__KEYS__||" "s|__WAIT__|100|" "s|__LIMIT__|20|" "s|__CLOSE__|no|" | grep -v "^$" | head -12 | sed 's/^/  open after: /' >> "$OUT"
  key "k:{ESC};;w:300;;k:{ESC}"
  rj $R/build/compare/save-all.js > /dev/null
  sleep 1
  git -C "$PROJECT" diff HEAD --stat -- '*.cs' >> "$OUT"
  git -C "$PROJECT" diff HEAD -U2 -- '*.cs' | head -${DIFF_LINES:-80} >> "$OUT"
  git -C "$PROJECT" reset -q --hard
  git -C "$PROJECT" clean -fdq -- '*.cs'
  rj $R/build/compare/reload-all.js > /dev/null
done
