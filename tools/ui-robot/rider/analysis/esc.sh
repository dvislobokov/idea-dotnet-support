# esc TEXT: escape for a JS string literal, then for a sed replacement with | as the delimiter
esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | sed -e 's/[\\&|]/\\&/g'; }
