// The IDE as Unity's external script editor (UNITY_PLAN.md, step 3): registered with CodeEditor, so Preferences | External Tools lists it;
// chosen there (or by the IDE's action, which the user asks for), a double click on a script or on a line of the console opens it in the IDE
// at the line, and the solution and the projects are generated as before — by the generator of the Rider package (or of Visual Studio's),
// called by reflection: they generate only for their own editor, and the plugin reads what they write. The IDE writes
// Temp/DotNetSupport/ide.json (its process, its launcher, a heartbeat); while it is fresh a file is opened through
// Temp/DotNetSupport/ide-commands, otherwise the launcher is started with the file.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using Unity.CodeEditor;
using UnityEditor;
using UnityEngine;
using Debug = UnityEngine.Debug;

namespace DotNetSupport.Unity.Editor
{
    [InitializeOnLoad]
    internal class ExternalEditor : IExternalCodeEditor
    {
        private const string PathPreference = "DotNetSupport.IdePath";
        private const string NamePreference = "DotNetSupport.IdeName";
        private const long HeartbeatTimeoutMs = 6000;

        private static readonly string ProjectDirectory = Path.GetDirectoryName(Application.dataPath) ?? ".";
        private static readonly string Folder = Path.Combine(ProjectDirectory, "Temp", "DotNetSupport");
        private static readonly string IdePath = Path.Combine(Folder, "ide.json");
        private static readonly string IdeCommands = Path.Combine(Folder, "ide-commands");

        // the extensions a double click opens in the IDE (the rest goes to the application of the system), as the Rider package has them
        private static readonly HashSet<string> Extensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            ".cs", ".uxml", ".uss", ".shader", ".compute", ".cginc", ".hlsl", ".glslinc", ".template", ".raytrace", ".json", ".rsp",
            ".asmdef", ".asmref", ".xaml", ".tt", ".t4", ".ttinclude", ".txt", ".xml", ".md",
        };

        private static long sequence;

        static ExternalEditor()
        {
            CodeEditor.Register(new ExternalEditor());
        }

        /// <summary>What the IDE wrote into ide.json; null when it has not, or the file is not readable.</summary>
        private sealed class Ide
        {
            public string Path;
            public string Name;
            public int ProcessId;
            public long Heartbeat;
        }

        private static Ide ReadIde()
        {
            try
            {
                if (!File.Exists(IdePath)) return null;
                var text = File.ReadAllText(IdePath);
                var ide = new Ide
                {
                    Path = JsonString(text, "path"),
                    Name = JsonString(text, "name"),
                    ProcessId = (int)JsonNumber(text, "pid"),
                    Heartbeat = JsonNumber(text, "heartbeat"),
                };
                if (string.IsNullOrEmpty(ide.Path)) return null;
                // remembered for Preferences | External Tools while the IDE is closed
                if (EditorPrefs.GetString(PathPreference) != ide.Path) EditorPrefs.SetString(PathPreference, ide.Path);
                if (!string.IsNullOrEmpty(ide.Name) && EditorPrefs.GetString(NamePreference) != ide.Name) EditorPrefs.SetString(NamePreference, ide.Name);
                return ide;
            }
            catch (IOException) { return null; }
            catch (UnauthorizedAccessException) { return null; }
        }

        // ide.json is flat and written by the plugin: its strings and numbers, without JsonUtility (a module a project may switch off)
        private static string JsonString(string json, string name)
        {
            var match = Regex.Match(json, "\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            return match.Success ? Regex.Unescape(match.Groups[1].Value) : null;
        }

        private static long JsonNumber(string json, string name)
        {
            var match = Regex.Match(json, "\"" + name + "\"\\s*:\\s*(-?\\d+)");
            return match.Success ? long.Parse(match.Groups[1].Value) : 0;
        }

        private static bool IsAlive(Ide ide) =>
            ide != null && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - ide.Heartbeat < HeartbeatTimeoutMs;

        public CodeEditor.Installation[] Installations
        {
            get
            {
                var ide = ReadIde();
                var path = ide?.Path ?? EditorPrefs.GetString(PathPreference);
                if (string.IsNullOrEmpty(path)) return Array.Empty<CodeEditor.Installation>();
                return new[] { new CodeEditor.Installation { Name = InstallationName(ide), Path = path } };
            }
        }

        private static string InstallationName(Ide ide)
        {
            var name = ide?.Name ?? EditorPrefs.GetString(NamePreference);
            return (string.IsNullOrEmpty(name) ? "IntelliJ IDE" : name) + " (C# Project Support)";
        }

        public bool TryGetInstallationForPath(string editorPath, out CodeEditor.Installation installation)
        {
            var known = Installations.FirstOrDefault(it => SamePath(it.Path, editorPath));
            installation = known;
            return !string.IsNullOrEmpty(known.Path);
        }

        private static bool SamePath(string a, string b) =>
            !string.IsNullOrEmpty(a) && !string.IsNullOrEmpty(b) &&
            string.Equals(Path.GetFullPath(a).TrimEnd('/', '\\'), Path.GetFullPath(b).TrimEnd('/', '\\'), StringComparison.OrdinalIgnoreCase);

        /// <summary>Whether the IDE is the editor Unity opens scripts with.</summary>
        internal static bool IsCurrent => SamePath(CodeEditor.CurrentEditorInstallation, ReadIde()?.Path ?? EditorPrefs.GetString(PathPreference));

        public void OnGUI()
        {
            EditorGUILayout.LabelField("Unity opens scripts in the IDE and generates the projects with the generator of the Rider or Visual Studio package.",
                EditorStyles.wordWrappedMiniLabel);
            var generator = Generator.Find();
            if (generator == null)
                EditorGUILayout.HelpBox("No project generator: add the package com.unity.ide.rider or com.unity.ide.visualstudio (Package Manager), " +
                                        "without one the IDE gets no .csproj files.", MessageType.Warning);
            else if (GUILayout.Button("Regenerate project files", GUILayout.Width(200))) generator.Sync();
        }

        public void SyncIfNeeded(string[] addedFiles, string[] deletedFiles, string[] movedFiles, string[] movedFromFiles, string[] importedFiles)
        {
            Generator.Find()?.SyncIfNeeded(addedFiles.Union(deletedFiles).Union(movedFiles).Union(movedFromFiles).ToArray(), importedFiles);
        }

        public void SyncAll()
        {
            Generator.Find()?.Sync();
        }

        public void Initialize(string editorInstallationPath)
        {
        }

        public bool OpenProject(string filePath = "", int line = -1, int column = -1)
        {
            // Assets | Open C# Project passes no file
            if (!string.IsNullOrEmpty(filePath) && !Extensions.Contains(Path.GetExtension(filePath))) return false;
            var generator = Generator.Find();
            if (generator != null && !File.Exists(Path.Combine(ProjectDirectory, Path.GetFileName(ProjectDirectory) + ".sln"))) generator.Sync();
            var file = string.IsNullOrEmpty(filePath) ? "" : Path.GetFullPath(filePath);
            var ide = ReadIde();
            if (IsAlive(ide))
            {
                SendOpen(file, line, column);
                AllowForeground(ide.ProcessId);
                return true;
            }
            return Launch(ide?.Path ?? EditorPrefs.GetString(PathPreference), file, line, column);
        }

        private static void SendOpen(string file, int line, int column)
        {
            try
            {
                Directory.CreateDirectory(IdeCommands);
                var name = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString("D13") + "-" + (sequence++ % 1000000).ToString("D6");
                var temporary = Path.Combine(IdeCommands, name + ".tmp");
                File.WriteAllText(temporary,
                    "{\"command\":\"open\",\"file\":\"" + Escape(file.Replace('\\', '/')) + "\",\"line\":" + line + ",\"column\":" + column + "}");
                // renamed when complete: the IDE takes `*.json` only
                File.Move(temporary, Path.Combine(IdeCommands, name + ".json"));
            }
            catch (IOException e) { Debug.LogWarning("[C# Project Support] the IDE was not asked to open " + file + ": " + e.Message); }
            catch (UnauthorizedAccessException e) { Debug.LogWarning("[C# Project Support] the IDE was not asked to open " + file + ": " + e.Message); }
        }

        private static string Escape(string value) => value.Replace("\\", "\\\\").Replace("\"", "\\\"");

        /// <summary>The launcher of the IDE with the project and the file: a running IDE takes them over, a closed one starts.</summary>
        private static bool Launch(string launcher, string file, int line, int column)
        {
            if (string.IsNullOrEmpty(launcher) || !File.Exists(launcher) && !Directory.Exists(launcher))
            {
                Debug.LogWarning("[C# Project Support] the IDE is not known yet: open the project in it once (" + (launcher ?? "no path") + ")");
                return false;
            }
            var arguments = "\"" + ProjectDirectory + "\"";
            if (!string.IsNullOrEmpty(file))
            {
                if (line > 0) arguments += " --line " + line;
                if (column > 0) arguments += " --column " + column;
                arguments += " \"" + file + "\"";
            }
            try
            {
                var macApp = Application.platform == RuntimePlatform.OSXEditor && launcher.EndsWith(".app", StringComparison.OrdinalIgnoreCase);
                var start = macApp
                    ? new ProcessStartInfo("open", "-na \"" + launcher + "\" --args " + arguments)
                    : new ProcessStartInfo(launcher, arguments);
                start.UseShellExecute = false;
                Process.Start(start);
                return true;
            }
            catch (Exception e)
            {
                Debug.LogWarning("[C# Project Support] the IDE could not be started (" + launcher + "): " + e.Message);
                return false;
            }
        }

        [DllImport("user32.dll")]
        private static extern bool AllowSetForegroundWindow(int processId);

        /// <summary>Windows gives the foreground to the process the user acted in: Unity hands it to the IDE, which then brings its window up.</summary>
        private static void AllowForeground(int processId)
        {
            if (Application.platform != RuntimePlatform.WindowsEditor || processId <= 0) return;
            try { AllowSetForegroundWindow(processId); }
            catch (Exception) { }
        }

        /// <summary>The project generator of the Rider package, else of the Visual Studio package; both are called by reflection.</summary>
        private sealed class Generator
        {
            private readonly object instance;
            private readonly MethodInfo sync;
            private readonly MethodInfo syncIfNeeded;

            private Generator(object instance, MethodInfo sync, MethodInfo syncIfNeeded)
            {
                this.instance = instance;
                this.sync = sync;
                this.syncIfNeeded = syncIfNeeded;
            }

            private static Generator cached;

            internal static Generator Find()
            {
                if (cached != null) return cached;
                cached = Of("Packages.Rider.Editor.ProjectGeneration.ProjectGeneration, Unity.Rider.Editor", null)
                         ?? Of("Microsoft.Unity.VisualStudio.Editor.ProjectGeneration, Unity.VisualStudio.Editor",
                             "Microsoft.Unity.VisualStudio.Editor.GeneratorFactory, Unity.VisualStudio.Editor");
                return cached;
            }

            private static Generator Of(string typeName, string factoryName)
            {
                try
                {
                    var type = Type.GetType(typeName);
                    if (type == null) return null;
                    object instance = null;
                    // Visual Studio's generators come from its factory (the legacy style is what Unity has always written)
                    var factory = factoryName == null ? null : Type.GetType(factoryName);
                    var getInstance = factory?.GetMethod("GetInstance", BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic);
                    if (getInstance != null)
                    {
                        var style = getInstance.GetParameters().FirstOrDefault()?.ParameterType;
                        if (style != null && style.IsEnum) instance = getInstance.Invoke(null, new[] { Enum.Parse(style, "Legacy") });
                    }
                    instance = instance ?? Activator.CreateInstance(type, true);
                    var flags = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
                    var sync = instance.GetType().GetMethod("Sync", flags, null, Type.EmptyTypes, null);
                    var syncIfNeeded = instance.GetType().GetMethods(flags).FirstOrDefault(m => m.Name == "SyncIfNeeded" && m.GetParameters().Length >= 2);
                    return sync == null ? null : new Generator(instance, sync, syncIfNeeded);
                }
                catch (Exception e)
                {
                    Debug.LogWarning("[C# Project Support] the project generator " + typeName + " is not usable: " + e.Message);
                    return null;
                }
            }

            internal void Sync()
            {
                try { sync.Invoke(instance, null); }
                catch (Exception e) { Debug.LogWarning("[C# Project Support] the projects were not generated: " + (e.InnerException ?? e).Message); }
            }

            internal void SyncIfNeeded(string[] affected, string[] imported)
            {
                if (syncIfNeeded == null)
                {
                    Sync();
                    return;
                }
                var parameters = syncIfNeeded.GetParameters();
                var arguments = new object[parameters.Length];
                arguments[0] = affected;
                arguments[1] = imported;
                for (var i = 2; i < parameters.Length; i++) arguments[i] = parameters[i].HasDefaultValue ? parameters[i].DefaultValue : null;
                try { syncIfNeeded.Invoke(instance, arguments); }
                catch (Exception e) { Debug.LogWarning("[C# Project Support] the projects were not updated: " + (e.InnerException ?? e).Message); }
            }
        }

        private const string PreviousPreference = "DotNetSupport.PreviousEditor";

        /// <summary>
        /// The IDE's command `use-as-editor`: Unity opens scripts in it from now on (Preferences | External Tools says so). The choice is
        /// Unity's for the whole machine: the editor chosen before is kept for `restore-editor`.
        /// </summary>
        internal static void UseAsEditor()
        {
            var path = ReadIde()?.Path ?? EditorPrefs.GetString(PathPreference);
            if (string.IsNullOrEmpty(path)) return;
            if (!IsCurrent) EditorPrefs.SetString(PreviousPreference, CodeEditor.CurrentEditorInstallation ?? "");
            CodeEditor.SetExternalScriptEditor(path);
        }

        /// <summary>The IDE's command `restore-editor`: the editor chosen before `use-as-editor` again.</summary>
        internal static void RestoreEditor()
        {
            if (!IsCurrent || !EditorPrefs.HasKey(PreviousPreference)) return;
            CodeEditor.SetExternalScriptEditor(EditorPrefs.GetString(PreviousPreference));
        }

        /// <summary>The IDE's command `open-asset <path> <line>`: Unity opens the asset as on a double click (the way its console does).</summary>
        internal static void OpenAsset(string arguments)
        {
            var parts = arguments.Trim().Split(' ');
            var line = parts.Length > 1 && int.TryParse(parts[parts.Length - 1], out var number) ? number : -1;
            var path = line > 0 ? string.Join(" ", parts, 0, parts.Length - 1) : arguments.Trim();
            var asset = AssetDatabase.LoadMainAssetAtPath(path);
            if (asset == null) Debug.LogWarning("[C# Project Support] no asset at " + path);
            else AssetDatabase.OpenAsset(asset, line);
        }
    }
}
