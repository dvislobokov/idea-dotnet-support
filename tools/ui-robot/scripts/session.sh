# Helpers for robot-driven debugger checks; source it from Git Bash in the repository root:
#   . tools/ui-robot/scripts/session.sh
export PYTHONIOENCODING=utf-8
ROBOT="python tools/ui-robot/robot.py"
ROBOT_SCRIPTS="tools/ui-robot/scripts"
ROBOT_TMP="${TEMP:-/tmp}/ui-robot.js"

# robot_js FILE [sed-expression ...]: run a script with its __PLACEHOLDERS__ filled in, print what it returns
robot_js() {
    local file="$1"; shift
    local args=()
    for expression in "$@"; do args+=(-e "$expression"); done
    if [ ${#args[@]} -gt 0 ]; then sed "${args[@]}" "$ROBOT_SCRIPTS/$file" > "$ROBOT_TMP"; else cp "$ROBOT_SCRIPTS/$file" "$ROBOT_TMP"; fi
    $ROBOT js "$ROBOT_TMP" 2>&1 | tr -d '\000-\010' | sed 's/^[^A-Za-z_$<[(]*t.\{0,2\}//'
}

state() { robot_js state.js | grep session; }

# wait_state SECONDS REGEX: poll until the session line matches
wait_state() {
    local line=""
    for _ in $(seq 1 $(( $1 / 2 ))); do
        ping -n 3 127.0.0.1 > /dev/null
        line=$(state)
        if echo "$line" | grep -qE "$2"; then break; fi
    done
    echo "$line"
}

evaluate() { robot_js evaluate.js "s|__EXPRESSION__|$1|" "s|__CHILDREN__|${2:-0}|"; }
set_value() { robot_js set_value.js "s|__NAME__|$1|" "s|__VALUE__|$2|"; }
stop_all() { robot_js stop_all.js > /dev/null; }
