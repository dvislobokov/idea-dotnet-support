// The link of the Unity editor with the IDE of C# Project Support (UNITY_PLAN.md, "Интеграция с редактором"). Files in Temp/DotNetSupport of
// the project, as Unity cleans Temp when it closes:
//   commands/<n>.txt   the IDE -> the editor: one command per file (refresh, compile, play, stop, pause, step, use-as-editor,
//                      restore-editor, open-asset <path> <line>; run-tests and cancel-tests of Tests/TestRuns.cs through IdeCommands), run
//                      in the order of the names
//   state.json         the editor -> the IDE: play mode, pause, compilation, the errors of the last compilation and a heartbeat every 2 s
// Files rather than a socket: they outlive the reload of the domain (every compilation), need no port, and the editor's update loop runs
// while it is in the background (measured on Unity 6000.6: a command runs within half a second, Play in 2.3 s, unfocused), when it does
// not look at the files of the project — so a save in the IDE becomes a refresh only through here.
using System;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Compilation;
using UnityEngine;

namespace DotNetSupport.Unity.Editor
{
    [InitializeOnLoad]
    internal static class IdeConnection
    {
        /// <summary>Of package.json: the IDE updates the package when its own is newer.</summary>
        private const string PackageVersion = "1.3.0";

        private static readonly string Folder = Path.Combine(Path.GetDirectoryName(Application.dataPath) ?? ".", "Temp", "DotNetSupport");
        private static readonly string Commands = Path.Combine(Folder, "commands");
        private static readonly string StatePath = Path.Combine(Folder, "state.json");

        private static double lastPoll;
        private static double lastBeat;
        private static bool compiling;
        private static int compileErrors;
        private static long compiledAt;

        // A static constructor runs once per domain: after every reload (a compilation, entering Play mode) it runs again, with the
        // subscriptions of the old domain gone with it. Unity may load the domain a few times in a row: nothing here is harmful twice.
        static IdeConnection()
        {
            EditorApplication.update += Update;
            EditorApplication.playModeStateChanged += _ => WriteState();
            EditorApplication.pauseStateChanged += _ => WriteState();
            CompilationPipeline.compilationStarted += _ =>
            {
                compiling = true;
                compileErrors = 0;
                WriteState();
            };
            CompilationPipeline.assemblyCompilationFinished += (_, messages) => compileErrors += messages.Count(m => m.type == CompilerMessageType.Error);
            CompilationPipeline.compilationFinished += _ =>
            {
                compiling = false;
                compiledAt = Now();
                WriteState();
            };
            WriteState();
        }

        private static void Update()
        {
            var now = EditorApplication.timeSinceStartup;
            if (now - lastBeat >= 2) WriteState();
            if (now - lastPoll < 0.2) return;
            lastPoll = now;
            if (!Directory.Exists(Commands)) return;
            string[] files;
            try { files = Directory.GetFiles(Commands, "*.txt"); }
            catch (IOException) { return; }
            Array.Sort(files, StringComparer.Ordinal);
            foreach (var file in files)
            {
                string command;
                try
                {
                    command = File.ReadAllText(file).Trim();
                    File.Delete(file);
                }
                catch (IOException) { continue; }
                catch (UnauthorizedAccessException) { continue; }
                Run(command);
            }
        }

        private static void Run(string command)
        {
            switch (command)
            {
                // a refresh imports what changed on disk and compiles the scripts if any of them did
                case "refresh":
                    AssetDatabase.Refresh();
                    break;
                case "compile":
                    CompilationPipeline.RequestScriptCompilation();
                    break;
                case "play":
                    EditorApplication.isPlaying = true;
                    break;
                case "stop":
                    EditorApplication.isPlaying = false;
                    break;
                case "pause":
                    EditorApplication.isPaused = !EditorApplication.isPaused;
                    break;
                case "step":
                    EditorApplication.Step();
                    break;
                case "use-as-editor":
                    ExternalEditor.UseAsEditor();
                    break;
                case "restore-editor":
                    ExternalEditor.RestoreEditor();
                    break;
                case var open when open.StartsWith("open-asset "):
                    ExternalEditor.OpenAsset(open.Substring("open-asset ".Length));
                    break;
                default:
                    if (!IdeCommands.TryRun(command)) Debug.LogWarning("[C# Project Support] unknown command from the IDE: " + command);
                    break;
            }
            WriteState();
        }

        private static readonly int ProcessId = System.Diagnostics.Process.GetCurrentProcess().Id;

        /// <summary>
        /// The state as JSON, written by hand: JsonUtility is a built-in module a project may switch off (com.unity.modules.jsonserialize),
        /// and the package must compile in every project.
        /// </summary>
        private static string StateJson() =>
            "{" +
            $"\"package\":\"{PackageVersion}\"," +
            $"\"unity\":\"{Application.unityVersion}\"," +
            $"\"processId\":{ProcessId}," +
            $"\"playing\":{Flag(EditorApplication.isPlayingOrWillChangePlaymode)}," +
            $"\"paused\":{Flag(EditorApplication.isPaused)}," +
            $"\"compiling\":{Flag(compiling || EditorApplication.isCompiling)}," +
            $"\"updating\":{Flag(EditorApplication.isUpdating)}," +
            $"\"compileErrors\":{compileErrors}," +
            $"\"compiledAt\":{compiledAt}," +
            $"\"ideIsEditor\":{Flag(ExternalEditor.IsCurrent)}," +
            $"\"heartbeat\":{Now()}" +
            "}";

        private static string Flag(bool value) => value ? "true" : "false";

        private static void WriteState()
        {
            lastBeat = EditorApplication.timeSinceStartup;
            try
            {
                Directory.CreateDirectory(Commands);
                // written beside and moved over: the IDE never reads half a file
                var temporary = StatePath + ".tmp";
                File.WriteAllText(temporary, StateJson());

                if (File.Exists(StatePath)) File.Replace(temporary, StatePath, null);
                else File.Move(temporary, StatePath);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    }
}
