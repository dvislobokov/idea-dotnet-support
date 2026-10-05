#!/usr/bin/env bash
# The oracle of the native C# formatter (CSharpFeature.FORMATTING, CSHARP_PSI_MIGRATION.md step 9): `dotnet format whitespace`
# on the files of debug-playground and an even sample of the corpus (runtime, aspnetcore), each in three variants: as it is,
# with every indent removed and the spaces between tokens doubled ("flat"), with `{` moved up to the line before ("knr").
# Prints the summary: identical files, files that differ by category (indent, spaces, line breaks), and the invariants
# (only whitespace changed, formatting twice changes nothing, no exceptions). Examples per category: build/format-oracle/report.txt;
# the inputs, Roslyn's and the native outputs: build/format-oracle/{input,expected,actual}. The Gradle log: build/format-oracle.log.
# Two styles of the native formatter: "dotnet" (Rider's lists off, must be exactly dotnet format) and "rider" (as Reformat Code
# runs it since 0.1.68: multi-line initializers, collection expressions, argument and parameter lists as Rider lays them out; a
# difference that is only theirs is counted as "only Rider's lists"). Rider's layout itself: golden pairs of jb cleanupcode in
# src/test/resources/formatting/rider.
#
# Usage: tools/csharp-psi/format-oracle.sh [sample] [-- <extra gradle args>]
#   sample                 files of the corpus (300 by default; the playground is always there)
#   -PformatOracle.corpus=<dir>       the corpus (default: .corpus of the repository, ../csharp-psi/.corpus, ~/csharp-psi/.corpus)
#   -PformatOracle.variants=orig,flat,knr
#   -PformatOracle.styles=dotnet,rider
#   -PformatOracle.examples=25        examples per category in the report
# Needs `dotnet` (SDK 8+) on PATH. Never part of `test`: unit tests do not run dotnet (golden pairs: src/test/resources/formatting).
set -u
cd "$(dirname "$0")/../.." || exit 2

sample=300
if [ $# -gt 0 ] && [ "$1" != "--" ]; then sample="$1"; shift; fi
[ "${1:-}" = "--" ] && shift

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        gradlew=./gradlew.bat
        : "${JAVA_HOME:=C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2026.1.4\jbr}"
        export JAVA_HOME ;;
    *) gradlew=./gradlew ;;
esac

mkdir -p build
log=build/format-oracle.log
"$gradlew" formatOracle "-PformatOracle.sample=$sample" "$@" > "$log" 2>&1
status=$?
grep -E "^ *(format oracle|(dotnet|rider)/(orig|flat|knr):|native formatter|dotnet format|report:)|FAILED|BUILD" "$log"
exit $status
