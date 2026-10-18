// Tests of the Unity Test Framework run from the IDE (UNITY_PLAN.md, step 4): the command `run-tests` starts a run through TestRunnerApi, and
// its events go to Temp/DotNetSupport/tests/<run>/events.jsonl as the IDE's test tree reads them for `dotnet test` (testStart / result with
// fqn, displayName, outcome, durationMs, message, stackTrace, stdout; runComplete at the end). An assembly of its own, compiled only when
// the project has com.unity.test-framework (versionDefines), so the package compiles in every project. A Play mode run reloads the domain:
// the callbacks are registered again by [InitializeOnLoad] and the run is found in SessionState.
using System;
using System.IO;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEditor.TestTools.TestRunner.Api;
using UnityEngine;

namespace DotNetSupport.Unity.Editor.Tests
{
    [InitializeOnLoad]
    internal static class TestRuns
    {
        private const string RunKey = "DotNetSupport.TestRun";
        private const string GuidKey = "DotNetSupport.TestRunGuid";

        private static readonly string Folder = Path.Combine(Path.GetDirectoryName(Application.dataPath) ?? ".", "Temp", "DotNetSupport", "tests");

        static TestRuns()
        {
            IdeCommands.Register("run-tests", Run);
            IdeCommands.Register("cancel-tests", _ => Cancel());
            TestRunnerApi.RegisterTestCallback(new Callbacks());
        }

        /// <summary>
        /// `run-tests <run> <EditMode|PlayMode>` and then one filter per line: `group:<regex>` (a class or a method, with its cases),
        /// `assembly:<name>`; no filter runs every test of the mode.
        /// </summary>
        private static void Run(string arguments)
        {
            var lines = arguments.Split('\n').Select(l => l.Trim()).Where(l => l.Length > 0).ToArray();
            var header = lines.FirstOrDefault()?.Split(' ') ?? Array.Empty<string>();
            if (header.Length < 2)
            {
                Debug.LogWarning("[C# Project Support] run-tests without a run and a mode: " + arguments);
                return;
            }
            var run = header[0];
            var mode = header[1] == "PlayMode" ? TestMode.PlayMode : TestMode.EditMode;
            var filter = new Filter
            {
                testMode = mode,
                groupNames = Values(lines, "group:"),
                assemblyNames = Values(lines, "assembly:"),
            };
            SessionState.SetString(RunKey, run);
            Write(run, "{\"event\":\"runStart\",\"mode\":\"" + mode + "\"}");
            var api = ScriptableObject.CreateInstance<TestRunnerApi>();
            var guid = api.Execute(new ExecutionSettings(filter));
            SessionState.SetString(GuidKey, guid ?? "");
        }

        private static string[] Values(string[] lines, string prefix)
        {
            var values = lines.Where(l => l.StartsWith(prefix)).Select(l => l.Substring(prefix.Length)).ToArray();
            return values.Length == 0 ? null : values;
        }

        private static void Cancel()
        {
            var guid = SessionState.GetString(GuidKey, "");
            if (guid.Length > 0) TestRunnerApi.CancelTestRun(guid);
            var run = SessionState.GetString(RunKey, "");
            if (run.Length > 0) Finish(run, "cancelled");
        }

        private static void Finish(string run, string how)
        {
            Write(run, "{\"event\":\"runComplete\",\"how\":\"" + how + "\"}");
            SessionState.EraseString(RunKey);
            SessionState.EraseString(GuidKey);
        }

        /// <summary>The run of the IDE in progress; the user's own runs of the Test Runner window are not written.</summary>
        private static string CurrentRun => SessionState.GetString(RunKey, "");

        private static void Write(string run, string line)
        {
            try
            {
                var directory = Path.Combine(Folder, run);
                Directory.CreateDirectory(directory);
                File.AppendAllText(Path.Combine(directory, "events.jsonl"), line + "\n", new UTF8Encoding(false));
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        private sealed class Callbacks : ICallbacks
        {
            public void RunStarted(ITestAdaptor testsToRun) { }

            public void RunFinished(ITestResultAdaptor result)
            {
                var run = CurrentRun;
                if (run.Length > 0) Finish(run, "finished");
            }

            public void TestStarted(ITestAdaptor test)
            {
                var run = CurrentRun;
                if (run.Length == 0 || test.IsSuite || test.HasChildren) return;
                Write(run, "{\"event\":\"testStart\"" + Common(test) + "}");
            }

            public void TestFinished(ITestResultAdaptor result)
            {
                var run = CurrentRun;
                var test = result.Test;
                if (run.Length == 0 || test.IsSuite || test.HasChildren) return;
                var outcome = result.TestStatus == TestStatus.Passed ? "Passed" : result.TestStatus == TestStatus.Failed ? "Failed" : "Skipped";
                Write(run, "{\"event\":\"result\"" + Common(test) +
                           ",\"resultName\":" + Quote(test.Name) +
                           ",\"outcome\":\"" + outcome + "\"" +
                           ",\"durationMs\":\"" + (long)(result.Duration * 1000) + "\"" +
                           ",\"message\":" + Quote(result.Message ?? (result.TestStatus == TestStatus.Skipped ? test.SkipReason : null)) +
                           ",\"stackTrace\":" + Quote(result.StackTrace) +
                           ",\"stdout\":" + Quote(result.Output) +
                           "}");
            }

            private static string Common(ITestAdaptor test)
            {
                var assembly = test.TypeInfo?.Assembly?.GetName().Name ?? "";
                return ",\"id\":" + Quote(test.UniqueName ?? test.FullName) + ",\"fqn\":" + Quote(test.FullName) + ",\"displayName\":" + Quote(test.Name) +
                       ",\"source\":" + Quote(assembly) + (test.TypeInfo != null ? ",\"type\":" + Quote(test.TypeInfo.FullName) : "");
            }
        }

        private static string Quote(string value)
        {
            if (value == null) return "null";
            var builder = new StringBuilder(value.Length + 2).Append('"');
            foreach (var c in value)
            {
                switch (c)
                {
                    case '"': builder.Append("\\\""); break;
                    case '\\': builder.Append("\\\\"); break;
                    case '\n': builder.Append("\\n"); break;
                    case '\r': builder.Append("\\r"); break;
                    case '\t': builder.Append("\\t"); break;
                    default:
                        if (c < ' ') builder.Append("\\u").Append(((int)c).ToString("x4"));
                        else builder.Append(c);
                        break;
                }
            }
            return builder.Append('"').ToString();
        }
    }
}
