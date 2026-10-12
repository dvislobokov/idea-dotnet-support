// Live events of `dotnet test` for the test tree of the plugin "C# Project Support". See DotNetSupport.TestLogger.csproj.
//
// Every event is one JSON object on its own line, written and flushed at once, in a file of its own process
// (`<directory>/<kind>-<pid>-<random>.jsonl`): a run of several projects or target frameworks has several vstest.console and
// datacollector processes at the same time. The plugin reads the files of the directory as they grow.
//
//   {"event":"runStart","sources":[...]}
//   {"event":"testStart","id":...,"fqn":...,"displayName":...,"source":...}                    (collector)
//   {"event":"testEnd","id":...,"fqn":...,"displayName":...,"source":...,"outcome":...}        (collector)
//   {"event":"result","id":...,"fqn":...,"displayName":...,"type":...,"method":...,"source":...,
//    "outcome":"Passed|Failed|Skipped|NotFound|None","durationMs":...,"message":...,"stackTrace":...,"stdout":...,"stderr":...}
//   {"event":"message","level":"Informational|Warning|Error","text":...}
//   {"event":"runComplete","canceled":...,"aborted":...,"error":...,"elapsedMs":...}

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Xml;
using Microsoft.VisualStudio.TestPlatform.ObjectModel;
using Microsoft.VisualStudio.TestPlatform.ObjectModel.Client;
using Microsoft.VisualStudio.TestPlatform.ObjectModel.DataCollection;
using Microsoft.VisualStudio.TestPlatform.ObjectModel.Logging;

namespace DotNetSupport.TestLogger
{
    /// <summary>`--logger:"DotNetSupport;Directory=<dir>" --test-adapter-path:<folder of this dll>`.</summary>
    [FriendlyName(FriendlyName)]
    [ExtensionUri("logger://DotNetSupport/TestLogger/v1")]
    public sealed class LiveTestLogger : ITestLoggerWithParameters
    {
        public const string FriendlyName = "DotNetSupport";

        private EventWriter? writer;

        public void Initialize(TestLoggerEvents events, string testRunDirectory) =>
            Initialize(events, new Dictionary<string, string?> { ["TestRunDirectory"] = testRunDirectory });

        public void Initialize(TestLoggerEvents events, Dictionary<string, string?> parameters)
        {
            parameters.TryGetValue("Directory", out var directory);
            directory ??= Environment.GetEnvironmentVariable(EventWriter.DirectoryVariable);
            if (string.IsNullOrEmpty(directory)) return;
            writer = new EventWriter(directory!, "logger");
            events.TestRunStart += (_, e) => writer.Write(new Json().Add("event", "runStart").Add("sources", e.TestRunCriteria?.Sources ?? Enumerable.Empty<string>()));
            events.TestRunMessage += (_, e) => writer.Write(new Json().Add("event", "message").Add("level", e.Level.ToString()).Add("text", e.Message));
            events.TestResult += (_, e) => writer.Write(Result(e.Result));
            events.TestRunComplete += (_, e) =>
            {
                writer.Write(new Json().Add("event", "runComplete").Add("canceled", e.IsCanceled).Add("aborted", e.IsAborted)
                    .Add("error", e.Error?.Message).Add("elapsedMs", (long)e.ElapsedTimeInRunningTests.TotalMilliseconds));
                writer.Dispose();
            };
        }

        private static Json Result(TestResult result)
        {
            var test = result.TestCase;
            string Messages(string category) =>
                string.Concat(result.Messages.Where(m => m.Category == category).Select(m => m.Text));
            return Describe(new Json().Add("event", "result"), test)
                .Add("outcome", result.Outcome.ToString())
                .Add("resultName", result.DisplayName)
                .Add("durationMs", (long)result.Duration.TotalMilliseconds)
                .Add("message", result.ErrorMessage)
                .Add("stackTrace", result.ErrorStackTrace)
                .Add("stdout", Messages(TestResultMessage.StandardOutCategory))
                .Add("stderr", Messages(TestResultMessage.StandardErrorCategory))
                .Add("info", Messages(TestResultMessage.AdditionalInfoCategory));
        }

        /// <summary>What identifies the test: the managed type and method are what TRX calls the class and the method.</summary>
        internal static Json Describe(Json json, TestCase test) => json
            .Add("id", test.Id.ToString())
            .Add("fqn", test.FullyQualifiedName)
            .Add("displayName", test.DisplayName)
            .Add("type", Property(test, "TestCase.ManagedType"))
            .Add("method", Property(test, "TestCase.ManagedMethod"))
            .Add("file", test.CodeFilePath)
            .Add("line", test.LineNumber)
            .Add("source", test.Source);

        private static string? Property(TestCase test, string id)
        {
            var property = test.Properties.FirstOrDefault(p => p.Id == id);
            return property == null ? null : test.GetPropertyValue(property) as string;
        }
    }

    /// <summary>
    /// `--collect:DotNetSupport.TestEvents`: the start of every test, which a logger is not told about, and its end with the outcome, which
    /// the logger hears of only with the next batch of results of the test host (a Stop that kills the host loses that batch). The
    /// directory comes from the environment, a collector has no parameters on the command line.
    /// </summary>
    [DataCollectorFriendlyName("DotNetSupport.TestEvents")]
    [DataCollectorTypeUri("datacollector://DotNetSupport/TestEvents/v1")]
    public sealed class TestEventsCollector : DataCollector
    {
        private EventWriter? writer;

        public override void Initialize(XmlElement? configurationElement, DataCollectionEvents events, DataCollectionSink dataSink,
            DataCollectionLogger logger, DataCollectionEnvironmentContext? environmentContext)
        {
            var directory = Environment.GetEnvironmentVariable(EventWriter.DirectoryVariable);
            if (string.IsNullOrEmpty(directory)) return;
            writer = new EventWriter(directory!, "collector");
            events.TestCaseStart += (_, e) => { if (e.TestElement != null) writer.Write(LiveTestLogger.Describe(new Json().Add("event", "testStart"), e.TestElement)); };
            events.TestCaseEnd += (_, e) =>
            {
                if (e.TestElement != null) writer.Write(LiveTestLogger.Describe(new Json().Add("event", "testEnd"), e.TestElement).Add("outcome", e.TestOutcome.ToString()));
            };
        }

        protected override void Dispose(bool disposing)
        {
            writer?.Dispose();
            base.Dispose(disposing);
        }
    }

    internal sealed class EventWriter : IDisposable
    {
        public const string DirectoryVariable = "DOTNET_SUPPORT_TEST_EVENTS";

        private readonly object gate = new object();
        private StreamWriter? stream;

        public EventWriter(string directory, string kind)
        {
            try
            {
                Directory.CreateDirectory(directory);
                var name = $"{kind}-{Process.GetCurrentProcess().Id}-{Guid.NewGuid():N}.jsonl";
                stream = new StreamWriter(new FileStream(Path.Combine(directory, name), FileMode.CreateNew, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete),
                    new UTF8Encoding(false)) { AutoFlush = true, NewLine = "\n" };
            }
            catch (Exception e)
            {
                Console.Error.WriteLine($"DotNetSupport test events: {e.Message}");
            }
        }

        public void Write(Json json)
        {
            lock (gate)
            {
                try
                {
                    stream?.WriteLine(json.ToString());
                }
                catch (Exception)
                {
                    // a full disk does not fail the tests
                }
            }
        }

        public void Dispose()
        {
            lock (gate)
            {
                stream?.Dispose();
                stream = null;
            }
        }
    }

    /// <summary>One flat JSON object; netstandard2.0 has no serializer without a package.</summary>
    internal sealed class Json
    {
        private readonly StringBuilder text = new StringBuilder("{");

        public Json Add(string name, string? value) => string.IsNullOrEmpty(value) ? this : Name(name).Quote(value!);
        public Json Add(string name, long value) => Name(name).Raw(value.ToString(System.Globalization.CultureInfo.InvariantCulture));
        public Json Add(string name, bool value) => Name(name).Raw(value ? "true" : "false");

        public Json Add(string name, IEnumerable<string> values)
        {
            Name(name).Raw("[");
            var first = true;
            foreach (var value in values)
            {
                if (!first) text.Append(',');
                first = false;
                Quote(value);
            }
            return Raw("]");
        }

        public override string ToString() => text.ToString() + "}";

        private Json Name(string name)
        {
            if (text.Length > 1) text.Append(',');
            return Quote(name).Raw(":");
        }

        private Json Raw(string value)
        {
            text.Append(value);
            return this;
        }

        private Json Quote(string value)
        {
            text.Append('"');
            foreach (var c in value)
            {
                switch (c)
                {
                    case '"': text.Append("\\\""); break;
                    case '\\': text.Append("\\\\"); break;
                    case '\n': text.Append("\\n"); break;
                    case '\r': text.Append("\\r"); break;
                    case '\t': text.Append("\\t"); break;
                    default:
                        if (c < ' ') text.Append("\\u").Append(((int)c).ToString("x4"));
                        else text.Append(c);
                        break;
                }
            }
            text.Append('"');
            return this;
        }
    }
}
