#!/usr/bin/env bash
# Runs project gates and prints only the lines that matter: corpus summaries, metrics, benchmark results, failures and
# the build result. The full Gradle output of every gate is kept in build/gates/<gate>.log.
#
# Usage: tools/csharp-psi/gates.sh <gate>... [-- <extra gradle args>]
#   test | build                     ./gradlew test | build (build includes the plugin ZIP)
#   corpus[:core]                    corpusTest (*CorpusTest) of csharp-psi-core, the only module with corpus gates yet
#   bench[:core]                     benchmark (*Benchmark) of csharp-psi-core
#   tree                             the whole-file tree gates (*TreeCorpusTest: roslyn-src, runtime, aspnetcore, playground,
#                                    runtime-netfx-cs7.3)
#   tree:<corpus>                    one of them: tree:roslyn, tree:runtime, tree:aspnetcore, tree:playground, tree:netfx
#   psi                              the PSI accessor gates (*PsiAccessorCorpusTest: roslyn-src, runtime, aspnetcore,
#                                    playground, parsing-tests, parsing-tests-doc)
#   fuzz                             CSharpParserFuzzTest (test) and CSharpParserFuzzCorpusTest (corpusTest)
#   mutation                         MutationOracleCorpusTest (corpusTest): seeded mutants of runtime files vs the oracle
#   semantic                         semanticGate of the root project: the resolver of names and types vs roslyndump semantics
#   :module:task                     any Gradle task, filtered the same way
# Examples:
#   tools/csharp-psi/gates.sh test corpus
#   tools/csharp-psi/gates.sh tree:runtime -- -Pcsharppsi.treeGate.limit=500
#   tools/csharp-psi/gates.sh corpus -- --tests '*RuntimeTreeCorpusTest*' -Pcsharppsi.corpus=/d/corpus
set -u
cd "$(dirname "$0")/../.." || exit 2

gates=(); extra=()
while [ $# -gt 0 ]; do
    if [ "$1" = "--" ]; then shift; extra=("$@"); break; fi
    gates+=("$1"); shift
done
[ ${#gates[@]} -gt 0 ] || { sed -n '2,/^set -u/p' "$0" | grep '^#' | sed 's/^# \{0,1\}//'; exit 2; }

# Windows (Git Bash): the batch wrapper and the JBR of the installed IDEA (no system JDK, CLAUDE.md).
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        gradlew=./gradlew.bat
        : "${JAVA_HOME:=C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr}"
        export JAVA_HOME ;;
    *) gradlew=./gradlew ;;
esac

module() { case "$1" in core|semantic|ide) echo ":csharp-psi-$1" ;; *) echo "unknown module: $1" >&2; exit 2 ;; esac; }

tasks_for() {
    case "$1" in
        test|build) echo "$1" ;;
        corpus) echo ":csharp-psi-core:corpusTest" ;;
        tree) echo ":csharp-psi-core:corpusTest --tests *TreeCorpusTest" ;;
        tree:roslyn) echo ":csharp-psi-core:corpusTest --tests *RoslynSrcTreeCorpusTest" ;;
        tree:runtime) echo ":csharp-psi-core:corpusTest --tests *RuntimeTreeCorpusTest" ;;
        tree:aspnetcore) echo ":csharp-psi-core:corpusTest --tests *AspnetcoreTreeCorpusTest" ;;
        tree:playground) echo ":csharp-psi-core:corpusTest --tests *PlaygroundTreeCorpusTest" ;;
        tree:netfx) echo ":csharp-psi-core:corpusTest --tests *RuntimeNetFrameworkCSharp73TreeCorpusTest" ;;
        psi) echo ":csharp-psi-core:corpusTest --tests *PsiAccessorCorpusTest" ;;
        mutation) echo ":csharp-psi-core:corpusTest --tests *MutationOracleCorpusTest" ;;
        semantic) echo ":semanticGate" ;;
        fuzz) echo ":csharp-psi-core:test --tests *CSharpParserFuzzTest :csharp-psi-core:corpusTest --tests *CSharpParserFuzzCorpusTest" ;;
        corpus:*) m=$(module "${1#corpus:}") || exit 2; echo "$m:corpusTest" ;;
        bench) echo ":csharp-psi-core:benchmark" ;;
        bench:*) m=$(module "${1#bench:}") || exit 2; echo "$m:benchmark" ;;
        :*) echo "$1" ;;
        *) echo "unknown gate: $1" >&2; exit 2 ;;
    esac
}

# Keeps what tests print themselves (the STANDARD_OUT blocks of test methods: corpus summaries, BENCH lines, failure
# samples) minus platform log lines, plus compile errors, failed tests/tasks and the build result.
filter() {
    sed 's/\r$//' "$1" | awk '
        /^[^ ].* > .* STANDARD_(OUT|ERROR)$/ { inTest = 1; print "-- " $1 " > " $3; next }
        /^[^ ].* > .* FAILED$/ { inTest = 0; failLines = 8; print; next }
        failLines > 0 && /^    / { failLines--; print; next }
        /^[^ ]/ { inTest = 0; failLines = 0 }
        /JPLISAgent|ASSERTION FAILED|\[cds\]/ { next }
        inTest && /^    / {
            if ($0 ~ /^    ([0-9]{4}-[0-9]{2}-[0-9]{2}|\[ *[0-9]+\] +(INFO|WARN|ERROR|DEBUG))/) next
            print substr($0, 5); next
        }
        /^e: |regressed|[0-9]+ tests? completed|^BUILD (SUCCESSFUL|FAILED)|^FAILURE: |^> Task .* FAILED/ { print }
        /^\* What went wrong/ { wrong = 1; print; next }
        wrong { if ($0 ~ /^$|^\* Try/) wrong = 0; else print; next }
    ' | head -n 400
}

mkdir -p build/gates
status=0
for gate in "${gates[@]}"; do
    spec=$(tasks_for "$gate") || exit 2
    read -r -a tasks <<< "$spec"
    log="build/gates/${gate//[:\/]/_}.log"
    start=$(date +%s)
    "$gradlew" "${tasks[@]}" --console=plain ${extra[@]+"${extra[@]}"} > "$log" 2>&1
    rc=$?
    echo "== $gate (${tasks[*]}) exit=$rc $(( $(date +%s) - start ))s  log: $log"
    filter "$log"
    [ $rc -eq 0 ] || status=$rc
done
exit $status
