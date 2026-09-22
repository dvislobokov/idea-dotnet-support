using DebugTarget;

var scenario = args.Length > 0 ? args[0] : "basic";
Console.WriteLine("scenario " + scenario); // BP:entry
switch (scenario)
{
    case "variables": Variables.Run(); break;
    case "stepping": Stepping.Run(); break;
    case "async": await AsyncCode.Run(); break;
    case "threads": Threads.Run(); break;
    case "exceptions": Exceptions.Run(); break;
    case "unhandled": Exceptions.Unhandled(); break;
    case "asyncunhandled": await Exceptions.AsyncUnhandled(); break;
    case "closures": Closures.Run(); break;
    case "output": Output.Run(); break;
    case "stdin": Output.Stdin(); break;
    case "loop": Output.Loop(); break;
    case "sleeper": Output.Sleeper(); break;
    case "env": Output.Env(args); break;
    case "exitcode": return 3;
    // the unusual ones
    case "spans": Atypical.Spans(); break;
    case "debugattrs": Atypical.DebugAttributes(); break;
    case "break": Atypical.Break(); break;
    case "deep": Atypical.Deep(3000); break;
    case "huge": Atypical.Huge(); break;
    case "evil": Atypical.Evil(); break;
    case "unicode": Atypical.Unicode(); break;
    case "patterns": Atypical.Patterns(); break;
    case "asynciter": await Atypical.AsyncIterator(); break;
    case "parallel": Atypical.ParallelLoop(); break;
    case "deadlock": Atypical.Deadlock(); break;
    case "staticctor": Atypical.StaticConstructors(); break;
    case "filters": Atypical.Filters(); break;
    case "threadcrash": Atypical.ThreadCrash(); break;
    case "stackoverflow": Atypical.Overflow(1); break;
    case "failfast": Environment.FailFast("fail fast requested"); break;
    case "envexit": Atypical.ExitFromThread(); break;
    case "dynamic": Atypical.DynamicCode(); break;
    case "multiline": Atypical.MultiLine(); break;
    case "generics2": Atypical.Generics(); break;
    case "finalizer": Atypical.Finalizer(); break;
    case "childproc": Atypical.ChildProcess(); break;
    case "refparams": Atypical.RefParams(); break;
}
Console.WriteLine("done"); // BP:done
return 0;
