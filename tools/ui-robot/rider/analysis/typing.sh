#!/usr/bin/env bash
# Typing assist probes against Rider. Usage: bash tools/ui-robot/rider/analysis/typing.sh > docs/rider-analysis/dumps/typing-assists.txt
cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/rider/analysis/rj.sh
ty() {
  echo; echo "=================== $1"; activate
  rj $A/typing.js "s|__FILE__|$P/Console/Editor/RiderAnalysis.cs|" "s|__MARK__|$(esc "$2")|" "s|__TYPE__|$(esc "$3")|" "s|__KEYS__|$(esc "$4")|" "s|__SYNC__|1000|" "s|__PAUSE__|${PAUSE:-250}|" "s|__AFTER__|${AFTER:-1200}|" "s|__SHOW__|${SHOW:-5}|" "s|__STEPS__|${STEPS:-no}|"
}
STEPS=yes ty "1 '(' after method name" "// P:c1" "        Place" "("
STEPS=yes ty "2 '\"' opens a pair" "// P:c1" "        var s = " "\"ab\";"
STEPS=yes ty "3 '[' and '{' pairs" "// P:c1" "        int[] a = " "[1, 2];"
STEPS=yes ty "4 interpolation braces" "// P:c1" "        var s = " "\$\"x{name}\";"
STEPS=yes ty "5 generic '<' after type" "// P:c1" "        var l = new List" "<int>();"
STEPS=yes ty "6 overtype ')' and ';' at line end" "// P:c1" "        Place(1, \"a\"" ");"
SHOW=8 ty "7 Enter between braces" "// P:c1" "        if (name == null) " "{{ENTER}"
SHOW=8 ty "8 '{' then Enter after method header (member level)" "// P:m1" "    public void NewOne() " "{{ENTER}"
SHOW=12 ty "9 /// doc template above method" "// P:m1" "" "///"
SHOW=8 ty "10 Enter inside doc comment" "/// <summary>Highlighting samples.</summary>" "    /// <remarks>first" "{ENTER}"
SHOW=6 ty "11 Ctrl+Shift+Enter: complete statement (call)" "// P:c1" "        Place(1, \"a\"" "{CSE}"
SHOW=8 ty "12 Ctrl+Shift+Enter: complete if" "// P:c1" "        if (name == null" "{CSE}"
SHOW=6 ty "13 ';' inside call moves out?" "// P:c1" "        Place(1, \"a\")" ";"
SHOW=6 ty "14 Enter in a string literal splits it" "// P:c1" "        var s = \"hello world\";" "{BS}{BS}{BS}{BS}{BS}{BS}{BS}{BS}{ENTER}"
SHOW=6 ty "15 '.' after method group auto-inserts ()?" "// P:c1" "        var c = name.Trim" "."
SHOW=8 ty "16 typing '}' reformats block" "// P:c1" "        if (true) {    Place(1,\"a\");   " "}"
SHOW=6 ty "17 Backspace removes pair" "// P:c1" "        var s = " "({BS}"
SHOW=6 ty "18 '=>' lambda" "// P:c1" "        Func<int, int> f = x =" ">"
SHOW=6 ty "19 auto-popup on letter (identifier start)" "// P:c1" "        " "na"
SHOW=8 ty "20 switch: Enter after 'case X:'" "// P:c1" "        switch (status) { case OrderStatus.New:" "{ENTER}"
