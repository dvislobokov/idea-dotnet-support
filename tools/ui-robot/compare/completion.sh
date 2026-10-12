# Completion latency and lists at four places of UserManager.RenameUser, in the IDE on ROBOT_PORT (both IDEs, the same text):
#   ROBOT_PORT=8583 PROJECT=<dir> OUT=<dump> bash tools/ui-robot/compare/completion.sh
export NO_PROXY=127.0.0.1 PYTHONIOENCODING=utf-8
R=$(git rev-parse --show-toplevel)
C=$R/tools/ui-robot/compare
F=$PROJECT/Jellyfin.Server.Implementations/Users/UserManager.cs
LINE=$(grep -n "ThrowIfInvalidUsername(newName);" "$F" | head -1 | cut -d: -f1)
: > "$OUT"
for probe in "member|newName.|BASIC" "prefix|Thro|BASIC" "new|var policy = new |BASIC" "smart-new|UserPolicy policy = new |SMART" "argument|ChangePassword(userId, |BASIC" "argument-smart|ChangePassword(userId, |SMART"; do
  IFS='|' read name typed kind <<< "$probe"
  for run in 1 2 3; do
    sed -e "s|__FILE__|$F|g" -e "s|__LINE__|$LINE|g" -e "s|__TYPE__|$typed|g" -e "s|__NEWLINE__|yes|g" -e "s|__KIND__|$kind|g" -e "s|__SYNC__|${SYNC:-600}|g" -e "s|__WAIT__|5000|g" -e "s|__LIMIT__|12|g" $C/complete_timed.js > $R/build/compare/ct.js
    r=$(timeout 120 python $R/tools/ui-robot/robot.py js $R/build/compare/ct.js 2>&1 | tr -d '\000-\010' | sed '1s/^[^f]*first/first/')
    echo "######## $name run $run «$typed» $kind" >> "$OUT"
    if [ $run -eq 3 ]; then echo "$r" >> "$OUT"; else echo "$r" | head -1 >> "$OUT"; fi
  done
done
