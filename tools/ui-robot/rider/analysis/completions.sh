#!/usr/bin/env bash
# Completion probes against Rider (docs/rider-analysis). Usage: bash tools/ui-robot/rider/analysis/completions.sh > docs/rider-analysis/dumps/completion.txt
cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/rider/analysis/rj.sh
p() { echo; echo "=================== $1"; shift; activate; comp "$@"; }

p "1 member access, auto-popup after '.' (string?)" "// P:c1" "        name" "." AUTO "" 25
p "2 empty statement, Ctrl+Space" "// P:c1" "        " "" BASIC "" 30
p "3 empty statement, Ctrl+Alt+Space (smart)" "// P:c1" "        " "" SMART "" 15
p "4 string. statics" "// P:c1" "        var e = string" "." AUTO "" 20
p "5 named argument prefix" "// P:c1" "        Place(qu" "" BASIC "" 12
p "6 next argument after two (named args offered?)" "// P:c1" "        Place(3, \"acme\", " "" BASIC "" 15
p "7 smart argument" "// P:c1" "        Place(3, \"acme\", true, " "" SMART "" 12
p "8 object initializer" "// P:c1" "        var o = new Order { " "" BASIC "" 15
p "8b object initializer, second property" "// P:c1" "        var o = new Order { Id = 1, " "" BASIC "" 15
p "9 new with expected type (smart)" "// P:c1" "        Order o = new " "" SMART "#1" 10
p "9b new with expected type (basic)" "// P:c1" "        Order o = new " "" BASIC "" 10
p "9c var o = new (basic)" "// P:c1" "        var o = new Ord" "" BASIC "#1" 10
p "10 enum members after type" "// P:c1" "        status = OrderStatus" "." AUTO "" 10
p "11 enum comparison, smart" "// P:c1" "        if (status == " "" SMART "#1" 10
p "11b enum comparison, basic" "// P:c1" "        if (status == " "" BASIC "" 10
p "12 switch case on enum" "// P:c1" "        switch (status) { case " "" BASIC "" 12
p "12b switch expression arm" "// P:c1" "        var label = status switch { " "" BASIC "" 12
p "13 property pattern" "// P:c1" "        if (order is { " "" BASIC "" 12
p "14 type pattern" "// P:c1" "        if (order.Status is " "" BASIC "" 12
p "15 lambda parameter member" "// P:c1" "        var big = items.Where(x => x" "." AUTO "" 12
p "15b lambda: method expecting delegate (smart)" "// P:c1" "        var big = items.Where(" "" SMART "" 12
p "16 LINQ query keywords" "// P:c1" "        var q = from i in items " "" BASIC "" 15
p "16b LINQ after where" "// P:c1" "        var q = from i in items where i > 0 " "" BASIC "" 15
p "17 nameof" "// P:c1" "        var n = nameof(" "" BASIC "" 15
p "18 typeof" "// P:c1" "        var t = typeof(" "" BASIC "" 15
p "19 generic argument" "// P:c1" "        var l = new List<" "" BASIC "" 15
p "19b generic argument second" "// P:c1" "        var d = new Dictionary<string, " "" BASIC "" 15
p "20 interpolation format specifier" "// P:c1" "        var s = \$\"{order.Total:" "" BASIC "" 15
p "21 DateTime.ToString format" "// P:c1" "        var s = DateTime.Now.ToString(\"" "" BASIC "" 20
p "21b string.Format placeholder" "// P:c1" "        var s = string.Format(\"{0:" "" BASIC "" 15
p "22 regex in Regex ctor" "// P:c1" "        var r = new Regex(@\"\\" "" BASIC "" 20
p "22b regex group" "// P:c1" "        var r = new Regex(@\"(?" "" BASIC "" 20
p "23 this." "// P:c1" "        this" "." AUTO "" 20
p "24 event subscription" "// P:c1" "        Changed += " "" BASIC "" 12
p "25 name suggestion after type" "// P:c1" "        StringBuilder " "" BASIC "" 10
p "25b name suggestion after type Order" "// P:c1" "        Order " "" BASIC "" 10
p "25c name suggestion foreach" "// P:c1" "        foreach (var " "" BASIC "" 10
p "26 throw new (smart)" "// P:c1" "        throw new " "" SMART "" 12
p "27 import completion (1st Ctrl+Space)" "// P:c1" "        JsonSeri" "" BASIC "" 12
TIME=2 p "27b import completion (2nd Ctrl+Space)" "// P:c1" "        JsonSeri" "" BASIC "#1" 12
p "27c import completion: unimported extension method" "// P:c1" "        items.AsParallel().WithDegree" "" BASIC "" 12
p "28 postfix list after expression" "// P:c1" "        items.fo" "" BASIC "" 15
SHOW=6 p "28b postfix foreach expansion" "// P:c1" "        items.foreach" "" BASIC "foreach" 15
SHOW=6 p "28c postfix if / notnull on reference" "// P:c1" "        name.notnull" "" BASIC "notnull" 10
SHOW=6 p "28d postfix var" "// P:c1" "        order.Total.var" "" BASIC "var" 10
SHOW=6 p "28e postfix return" "// P:c1" "        name.return" "" BASIC "return" 10
SHOW=6 p "28f postfix await on task" "// P:a1" "        client.GetStringAsync(\"u\").await" "" BASIC "" 10
SHOW=8 p "29 live template for" "// P:c1" "        for" "" BASIC "for" 10
SHOW=8 p "29b live template foreach" "// P:c1" "        foreach" "" BASIC "foreach" 10
SHOW=8 p "29c live template cw" "// P:c1" "        cw" "" BASIC "cw" 10
SHOW=8 p "29d live template try" "// P:c1" "        try" "" BASIC "try" 10
SHOW=8 p "29e live template if" "// P:c1" "        if" "" BASIC "if" 10
SHOW=8 p "30 live template prop (member)" "// P:m1" "    prop" "" BASIC "prop" 10
SHOW=8 p "30b live template ctor (member)" "// P:m1" "    ctor" "" BASIC "ctor" 10
p "31 member level keywords" "// P:m1" "    pu" "" BASIC "" 15
SHOW=10 p "32 override completion" "// P:o1" "    override " "" BASIC "" 15
SHOW=10 p "32b override pick Area" "// P:o1" "    public override " "" BASIC "#1" 15
p "33 base." "// P:o1" "    public override string Name => base" "." AUTO "" 15
p "34 attribute" "// P:m1" "    [Obs" "" BASIC "" 10
p "35 XML doc tag" "// P:m1" "    /// " "<" AUTO "" 20
p "35b XML doc see cref" "// P:m1" "    /// <see cref=\"" "" BASIC "" 15
p "36 preprocessor directive" "// P:m1" "" "#" AUTO "" 20
p "37 using directive namespace" "using System.Text.RegularExpressions;" "using System.Coll" "" BASIC "" 12
p "38 await in async method (smart string)" "// P:a1" "        string s = " "" SMART "" 12
p "38b await: Task-returning member in async" "// P:a1" "        var s = client.GetStr" "" BASIC "#1" 12
p "39 return in int method (smart)" "// P:r1" "        return " "" SMART "" 12
p "40 CancellationToken argument" "// P:c1" "        Task.Delay(100, " "" BASIC "" 10
p "41 type-level (namespace) keywords" "// P:t1" "pub" "" BASIC "" 15
p "42 Console.WriteLine argument (smart)" "// P:c1" "        Console.WriteLine(" "" SMART "" 12
p "43 interpolation expression" "// P:c1" "        var s = \$\"{" "" BASIC "" 15
p "44 inherited members on derived" "// P:c1" "        new Circle()" "." AUTO "" 15
p "45 extension method on string" "// P:c1" "        name.IsB" "" BASIC "#1" 10
p "46 catch exception type" "// P:c1" "        try { } catch (" "" BASIC "" 12
p "47 var type keyword vs types" "// P:c1" "        va" "" BASIC "" 10
p "48 local function / lambda return smart" "// P:c1" "        Func<int, bool> f = " "" SMART "" 10
