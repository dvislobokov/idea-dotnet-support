#!/usr/bin/env bash
# Runs every scenario; each group gets a time limit so that one hang does not stop the rest.
mkdir -p out
run() { local name=$1; shift; echo "##### $name"; timeout "$LIMIT" python3 "$@" > "out/$name.txt" 2>&1; echo "exit=$? lines=$(wc -l < out/$name.txt)"; pkill -9 -f "App.dll" 2>/dev/null; pkill -9 -f dotnet-debugger 2>/dev/null; true; }
LIMIT=900
run variables batch1.py variables
run stepping batch1.py stepping async_code
run threads batch1.py threads exceptions
run closures batch1.py closures output
run b2a batch2.py async_stepping spans debugattrs debugger_break deep
LIMIT=1500 run b2b batch2.py huge evil unicode_names
run b2c batch2.py patterns asynciter parallel deadlock staticctor filters
run b2d batch2.py crashes
run b2e batch2.py dynamic_code multiline generics finalizer childproc refparams
run b3a batch3.py bp_features
run b3b batch3.py process_control attach launch_variants
run b3c batch3.py protocol_abuse
run verify_garbage verify_garbage.py
run verify_attach verify_attach.py
run verify_misc verify_misc.py
run b3d batch3.py paths_and_builds server_mode
