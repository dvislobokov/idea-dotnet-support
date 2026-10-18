// The console of the Unity editor for the IDE (UNITY_PLAN.md, step 2): every message of the console and every message of the compiler,
// one JSON object per line appended to Temp/DotNetSupport/console.jsonl. The first line names the file ({"kind":"start","id":...,"pid":...}):
// the IDE reads on from where it stopped while the id is the same, and from the start when it is not (a file grown too big and begun again;
// another editor, by the pid, clears the log). Messages come on any thread (logMessageReceivedThreaded) and are written from the editor's update loop, before a reload of the
// domain and when the editor quits.
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using UnityEditor;
using UnityEditor.Compilation;
using UnityEngine;

namespace DotNetSupport.Unity.Editor
{
    [InitializeOnLoad]
    internal static class ConsoleLog
    {
        private const long MaxLength = 8 * 1024 * 1024;

        private static readonly string Folder = Path.Combine(Path.GetDirectoryName(Application.dataPath) ?? ".", "Temp", "DotNetSupport");
        private static readonly string LogPath = Path.Combine(Folder, "console.jsonl");

        // the message of the compiler as the console shows it: they come structured from the compilation pipeline (with the place and the code)
        private static readonly Regex CompilerMessage = new Regex(@"^[^\r\n]+\(\d+,\d+\): (error|warning) [A-Za-z]+\d+: ", RegexOptions.Compiled);

        private static readonly object Lock = new object();
        private static readonly List<string> Pending = new List<string>();
        private static volatile bool playing;
        private static readonly int ProcessId = System.Diagnostics.Process.GetCurrentProcess().Id;

        static ConsoleLog()
        {
            playing = EditorApplication.isPlayingOrWillChangePlaymode;
            Application.logMessageReceivedThreaded += OnLog;
            EditorApplication.update += Flush;
            EditorApplication.playModeStateChanged += change =>
            {
                playing = EditorApplication.isPlayingOrWillChangePlaymode;
                // before the first message of the game (Awake): the IDE clears its log here when it clears on play, as the console does
                if (change == PlayModeStateChange.ExitingEditMode) Add("{\"kind\":\"play-started\",\"t\":" + Now() + "}");
            };
            AssemblyReloadEvents.beforeAssemblyReload += Flush;
            EditorApplication.quitting += Flush;
            CompilationPipeline.compilationStarted += _ => Add("{\"kind\":\"compile-started\",\"t\":" + Now() + "}");
            CompilationPipeline.assemblyCompilationFinished += (assembly, messages) =>
            {
                foreach (var message in messages) Add(Compiler(message));
            };
        }

        private static void OnLog(string text, string stack, LogType type)
        {
            if (text != null && CompilerMessage.IsMatch(text)) return;
            var line = new StringBuilder(128 + (text?.Length ?? 0) + (stack?.Length ?? 0));
            line.Append("{\"kind\":\"log\",\"t\":").Append(Now())
                .Append(",\"type\":\"").Append(type.ToString()).Append('"')
                .Append(",\"play\":").Append(playing ? "true" : "false")
                .Append(",\"text\":").Append(Quote(text))
                .Append(",\"stack\":").Append(Quote(stack))
                .Append('}');
            Add(line.ToString());
        }

        private static string Compiler(UnityEditor.Compilation.CompilerMessage message)
        {
            // "Assets/Player.cs(27,40): error CS1002: ; expected": the code and the text after it
            var text = message.message ?? "";
            var code = "";
            var match = Regex.Match(text, @"\): (?:error|warning|info) ([A-Za-z]+\d+): ");
            if (match.Success)
            {
                code = match.Groups[1].Value;
                text = text.Substring(match.Index + match.Length);
            }
            return "{\"kind\":\"compiler\",\"t\":" + Now() +
                   ",\"type\":\"" + (message.type == CompilerMessageType.Error ? "error" : message.type == CompilerMessageType.Warning ? "warning" : "info") + "\"" +
                   ",\"code\":" + Quote(code) +
                   ",\"text\":" + Quote(text) +
                   ",\"file\":" + Quote((message.file ?? "").Replace('\\', '/')) +
                   ",\"line\":" + message.line +
                   ",\"column\":" + message.column +
                   "}";
        }

        private static void Add(string line)
        {
            lock (Lock) Pending.Add(line);
        }

        private static void Flush()
        {
            string[] lines;
            lock (Lock)
            {
                if (Pending.Count == 0) return;
                lines = Pending.ToArray();
                Pending.Clear();
            }
            try
            {
                Directory.CreateDirectory(Folder);
                var info = new FileInfo(LogPath);
                var builder = new StringBuilder();
                if (!info.Exists || info.Length > MaxLength)
                    builder.Append("{\"kind\":\"start\",\"id\":\"").Append(Guid.NewGuid().ToString("N")).Append("\",\"pid\":").Append(ProcessId).Append(",\"t\":").Append(Now()).Append("}\n");
                foreach (var line in lines) builder.Append(line).Append('\n');
                if (!info.Exists || info.Length > MaxLength) File.WriteAllText(LogPath, builder.ToString(), new UTF8Encoding(false));
                else File.AppendAllText(LogPath, builder.ToString(), new UTF8Encoding(false));
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        private static string Quote(string value)
        {
            if (value == null) return "\"\"";
            var builder = new StringBuilder(value.Length + 2);
            builder.Append('"');
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

        private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    }
}
