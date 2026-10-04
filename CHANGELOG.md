# Changelog

Every feature is a new version `0.1.x`. The build puts these sections into the change notes of the plugin
(Settings | Plugins → What's New) and fails when there is no section for the current `pluginVersion`.

## 0.1.42

- Run of a .NET Framework project of the old format starts the program its build has made (`bin\Debug\App.exe`), in its output
  folder, as Visual Studio does; `dotnet run` cannot run such a project. Program arguments, the working directory and the environment
  of the configuration apply. Stop closes a WPF or Windows Forms program at once. The output of a console program is read in the OEM
  code page of Windows, the one its console writes in
- Build of a single old-format project with `packages.config` restores its packages: the build is given the folder of its solution,
  as Visual Studio does

## 0.1.41

- .NET Framework projects of the old format (without `Sdk`), and the solutions with them, are built by MSBuild of Visual Studio or
  Build Tools, found by `vswhere`: WPF markup is compiled, `packages.config` is restored by the build, web targets are there. The .NET
  SDK used to skip the XAML of such a WPF project and fail with "no Main". Settings | Tools | .NET | Toolset and Build has "MSBuild
  version": Auto (old-format projects by Visual Studio, the rest by the .NET SDK), the .NET SDK always, or a chosen Visual Studio for
  everything. Without Visual Studio the plugin says so once, with a link to Build Tools
- The Solution view of an old-format project reads the targets of Visual Studio too (`$(VSToolsPath)`), instead of skipping them

## 0.1.40

- Groundwork for the plugin's own C# code model (no change in behaviour yet): the plugin is built with three new, still empty modules,
  and every feature of the language server can now be switched between the server and a built-in implementation. The switch shows
  up on Settings | Tools | .NET | Language Server for a feature once its built-in implementation exists; today there is none, and
  everything works as before

## 0.1.39

- Unit Tests: the output of `dotnet test` itself (the summary, the results file) stays at the root of the run instead of being shown as
  the output of the test that happened to be running; a test sees only its own output
- Unit Tests: Stop no longer marks as interrupted a test that has already finished — its outcome is kept even when its details had
  not come yet
- New Solution is a button on the Welcome screen right after New Project (it used to be hidden behind "⋮"), in IntelliJ IDEA and in
  the IDEs whose New Project button is a group, like GoLand
- IL Viewer: a field without an initializer shows its declaration with a note that a field has no IL of its own; a line with no IL
  says so once; the note wraps instead of being cut
- New Solution: the description of a template wraps instead of being cut at the right edge

## 0.1.38

- Aspire: the AppHost project is recognized (Aspire 8, 9 and 13) — its own icon, its run configuration first. The dashboard login link
  is clickable in the console and kept for **Open Dashboard** in the Services tool window, and the browser opens straight on it. Debug of
  the AppHost attaches the debugger to every project service it starts, each in its own tab, again after a restart of the resource; Stop
  ends them all. A breakpoint at the very start of a service may still be missed: the debugger attaches once the process runs
- Services: the links of a row (the address of a web project, Restart of `dotnet watch`) now show up — the platform only made existing
  text clickable

## 0.1.37

- Find Usages for C# is grouped as in Rider, behind the standard Group by Usage Type / Module / File Structure toggles of the Usages
  view: by usage kind (read, write, invocation, `nameof`, attribute, `new`, base type, `using`, `typeof`, type check, cast, type
  argument, doc comment), by .NET project and by the type and member the usage is in

## 0.1.36

- Unit Tests: results appear while `dotnet test` runs (VSTest: xUnit, NUnit, MSTest), as in Rider — each test shows as running when it
  starts, then as passed, failed or skipped with its duration, message, stack trace and output; Stop marks the test that was running;
  the TRX report at the end fills in anything missed. The plugin's test logger is built once per machine on the first run. Projects on
  Microsoft Testing Platform still get their results at the end

## 0.1.35

- **Publish...** for applications (the Solution view and the .NET menu), as in Rider: configuration, target framework, target runtime,
  deployment mode (framework-dependent / self-contained), single file, ReadyToRun, trimming, target location, with the command shown
  before it runs. `.pubxml` publish profiles are read — those of Visual Studio included — and written by Save as Profile; a container
  image is published with `dotnet publish -t:PublishContainer`. The output goes to the Build tool window, a notification opens the
  folder; Save as run configuration makes a ".NET Publish" run configuration

## 0.1.34

- Security: the packages folders named in `obj/project.assets.json` are read only when they are local (or the folder of `NUGET_PACKAGES`):
  a cloned repository with such a file pointing at `\\host\share` no longer makes the IDE log on to that host, which would give it the
  NTLM hash of the user. Applies to the package schemas of appsettings and to the assembly index

## 0.1.33

- `appsettings.json` and `appsettings.<Environment>.json` get completion and checks for the sections your code reads — in any project,
  not only ASP.NET Core: `Configure<T>(GetSection(...))`, `AddOptions<T>().Bind / BindConfiguration`, `GetSection(...).Get<T>()`,
  `GetValue<T>`, `config["A:B"]`, or a `// appsettings: Section` comment above a class. Keys come with their XML-doc descriptions and
  defaults, enums with their values; a value of the wrong type is a warning, a key the options class does not have is a weak warning.
  Works together with the ASP.NET Core sections of SchemaStore and the `ConfigurationSchema.json` of NuGet packages, and follows a C#
  file a second after it changes, even before it is saved
- Security: the Solution view and the IL viewer no longer evaluate the MSBuild logic of a project the IDE has not been told to trust;
  the IL viewer follows only a local path to a PDB named inside an assembly, never a network one

## 0.1.32

- **IL Viewer** (.NET | IL Viewer), as in Rider: the IL of the method at the caret, from the last build, follows the editor. The PDB tells
  which code is on the line — the method, the state machine of an async method or an iterator, a lambda, a local function; the lines
  of C# and their IL are highlighted both ways. A banner says when the source has changed since the build, with a Build button

## 0.1.31

- The C# language server in the status bar shows its CPU and memory next to the C# icon, updated every two seconds, instead of the
  name of the solution (that one is in the tooltip and the popup)

## 0.1.30

- **New Solution** window as in Rider — File | New, the .NET menu and the Welcome screen: project kinds and templates, target framework
  and the SDK to pin in `global.json`, language, the template description and advanced settings, a Git repository

## 0.1.29

- Memory Dump (.NET Monitor) shows the retained size of every type and a tree of dominators — who keeps how much of the heap alive —
  computed in the background by a new diagnostics helper on ClrMD
- With the registry key `dotnet.debugger.attach.netFramework`, Run | Attach to Process also offers native hosts that load the .NET
  Framework runtime themselves (IIS `w3wp.exe`, Office, Windows PowerShell), 32-bit ones included

## 0.1.28

- Projects are evaluated by MSBuild itself, as a build sees them (conditions, properties, imports), through a helper that stays running:
  Debug finds the built assembly without a `dotnet msbuild` call per launch, and an old-style (non-SDK) project shows in the Solution
  view exactly the files it lists; the others appear only with Show All Files

## 0.1.27

- NuGet: a feed the IDE cannot reach (proxy, certificates, credentials) is searched through a .NET helper that goes to the network like
  the `dotnet` CLI — nuget.config credentials and credential providers included; local folder feeds and V2 feeds are searched too.
  The journal says which way every request went
- NuGet Restore (the NuGet window, .NET | NuGet | Restore and the automatic restore) restores `packages.config` projects into the
  packages folder of their solution (`repositoryPath` of nuget.config respected); a package that is not there is marked "not restored"
  in Dependencies

## 0.1.26

- `dotnet watch`: the Hot Reload state (watching, changes applied, restart needed after a rude edit, build failed) in the row of the
  Services tool window and colored in the run console, with **Restart** there and in the toolbar of the console. Under an IDE
  `dotnet watch` does not read its keys (Ctrl+R, the y/n answer), so Restart runs the configuration again in the same tab

## 0.1.25

- Go to Base (Ctrl+U) on a method, property, event or indexer jumps to the member it overrides or implements in the base classes and
  interfaces: the nearest base class first, then the interfaces; several — a list. A base type that is not in the sources is gone to
  as a type. Needs the C# language server

## 0.1.24

- Legacy projects with `packages.config`: their packages are shown under Dependencies → Packages and as installed in the NuGet window,
  where install, update and remove are disabled with an explanation instead of writing a `PackageReference` into the project

## 0.1.23

- Run | Attach to Process can offer .NET Framework processes (a managed `.exe` without `runtimeconfig.json`), for a debug adapter that
  debugs the desktop CLR. Off by default: registry key `dotnet.debugger.attach.netFramework`

## 0.1.22

- Several projects selected in the Solution view: **Run N Projects** / **Debug N Projects** in the context menu, as Rider's "Run
  Multiple Projects". The projects are built one after another first, then launched together, each in its own tab of Services
- The builds a debug launch waits for go one after another, so a Compound configuration with several .NET projects no longer builds
  their shared dependencies twice at the same time

## 0.1.21

- The journal of NuGet (.NET | Plugin Logs, category `nuget`, and the Log tab of the NuGet window) now has every request to a feed:
  the URL, the route the IDE takes to it (direct or through which proxy, with or without stored credentials), the HTTP status, the size
  and the time of the answer, the services the feed announces, and the result of every search and version lookup by feed. A failure
  is described by its class and causes (`UnknownHostException: host`, `SSLHandshakeException: PKIX path building failed`, `HTTP 401`)
  with the setting of the IDE that usually fixes it — the feeds are read by the HTTP client of the IDE, which has its own proxy,
  certificates and credentials, so the `dotnet` CLI can reach a feed the IDE cannot. Nothing is said "once per session" any more: a
  feed that is down says so on every request. The first request also writes the proxy settings of the IDE and the proxy variables of
  its environment
- The list of the NuGet window says "none, N of M feeds did not answer" instead of an empty "Available Packages: 0" when a feed failed
- The sources the window searches are in the journal as the CLI lists them, with the local feeds that are skipped

## 0.1.20

- Package completion in project files looks as in Rider: the id and, in gray, the version — nothing else, so the popup
  is narrow. The next characters narrow the list in place; the feed is asked again only when nothing is left, so the
  list no longer rebuilds (and flickers) on every keystroke. A `.` or `-` goes on with the id instead of inserting the
  selected package
- NuGet window: the rows of the list look as in Rider: the id, the installed version and the name of the feed, the latest
  version in the color of a link at the right edge, a vulnerable or deprecated package marked by an icon; no description
  in the row. The list keeps the width of the window, so the versions no longer vanish behind its right edge after a
  refresh. The rows carry no action icons: install, update and remove are the buttons of the card

## 0.1.19

- **.NET | Plugin Logs**: the journal of the plugin in a tool window — what it started, what came back, what failed and why,
  one line per event, nothing of the IDE in it. The same journal is in `~/idea-dotnet-logs/plugin/plugin-<day>.log`
  (.NET | Open Logs Folder, formerly Show Plugin Logs). Failures of external programs are described by their exit code
  and last lines, not by stack traces
- The C# language server explains why it did not start: the runtime its host asked for, where the host looked and what
  it found there (`You must install or update .NET`), with the `dotnet` the plugin uses and its runtimes in the journal.
  The server is started with `DOTNET_ROOT` set to the installation of that `dotnet`, so a .NET 10 in a folder of its own
  is found even when the machine registers an older one
- Notifications for what used to vanish silently: the server process that could not be created, the debug adapter that
  exited in the middle of a session, the indexer of assemblies that could not be built. Every error notification has a
  "Plugin Logs" button
- The check at the opening of a solution lists the SDKs and the runtimes in the journal, and the ".NET 10 is missing"
  dialog looks at the runtimes too, not only the SDKs

## 0.1.18

- Settings | Tools | .NET: **Additional search folders** for the dotnet CLI. Each folder and its immediate `dotnet*`
  subfolders are scanned for a `dotnet` host (so `/usr/share` finds `/usr/share/dotnet-sdk-8.8.403`), ahead of PATH. The
  same list can be set on every machine through the `DOTNET_SUPPORT_SEARCH_PATHS` environment variable — for corporate
  installs in non-standard directories

## 0.1.17

- Editor, gray text: `break;` on the first (empty) line of a `case` / `default` section

## 0.1.16

- Editor, gray text (Tab to accept): a `;` after a statement that is missing it (the same safe cases as Complete Statement), and
  ` => throw new NotImplementedException();` after a method header in a class / struct / record

## 0.1.15

- Editor: **Complete Statement** (Ctrl+Shift+Enter) adds the missing `;` at the end of a statement and opens a new line. Conservative
  without a parser — only clear statements (an assignment, a call, `return` / `throw` / `break` …); a header like `if (x)` or a declaration
  like `void M()` is never turned into `...;`

## 0.1.14

- Editor: three more postfix templates — `.for` (`for (var i = 0; i < expr; i++)`), `.forr` (reverse) and `.cast` (`((T)expr)`, with the
  type placeholder selected)

## 0.1.13

- When a .NET solution is opened and no .NET 10 SDK is installed, a modal dialog now says the C# language server (Roslyn) needs the .NET 10
  SDK and will not start on .NET 8 / 9 — with buttons to download it or open the settings. Replaces relying on the easy-to-miss balloon

## 0.1.12

- Editor: the other occurrences of the identifier under the caret are now highlighted while the C# server is still loading (by matching
  the token text); once the server covers the file, its accurate `documentHighlight` takes over

## 0.1.11

- Editor: pressing Enter inside a `//` line comment or a `/* */` block comment now continues the comment on the next line (`// ` for a line
  comment, ` * ` aligned for a block). The `///` doc comment already did this

## 0.1.10

- Editor: **Extend / Shrink Selection** (Ctrl+W / Ctrl+Shift+W) now grows structurally in C# — word → contents of the enclosing `()` / `[]`
  / `{}` → the brackets too → the next pair out, and the string or comment the caret is in

## 0.1.9

- Editor, `.http` files: **Insert Development JWT** in the context menu runs `dotnet user-jwts create` for the ASP.NET Core project and
  inserts an `Authorization: Bearer <token>` header at the caret — to call an `[Authorize]` endpoint without hand-making a token

## 0.1.8

- NuGet: **Consolidate Package Versions** (.NET → NuGet menu and the NuGet window toolbar) finds packages referenced at different versions
  across the solution's projects and brings them to the highest version used, with a preview of the changes

## 0.1.7

- When a solution with an ASP.NET Core web project is opened, the plugin checks the HTTPS development certificate and, if it is not trusted,
  offers to trust it (`dotnet dev-certs https`) — so the first `https://` run does not warn in the browser

## 0.1.6

- Editor: **Run C# File with dotnet** in the context menu of a standalone `.cs` file (one that no project owns) runs `dotnet run <file>` —
  a file-based app, .NET 10+ — in a console with a Stop button

## 0.1.5

- NuGet window: right-click a package → **Why Is This Installed?** runs `dotnet nuget why` and shows the dependency paths that pulled it
  in, per target framework (needs .NET SDK 8.0.400+)

## 0.1.4

- NuGet window: installed packages that are **vulnerable** or **deprecated** are now flagged — a tag in the list (severity for a
  vulnerability, "Deprecated" otherwise) and a line in the package card with a link to the advisory. Uses
  `dotnet list package --vulnerable` and `--deprecated`; outdated packages already showed the "→ latest" arrow

## 0.1.3

- NuGet window, Sources tab: the **Enabled** checkbox is now clickable — a click enables or disables the feed
  (`dotnet nuget enable | disable source`), instead of only looking like a checkbox

## 0.1.2

- Code Metrics: Calculate Code Metrics on a project or solution (.NET menu and the Solution view context menu) opens the **Code Metrics**
  tool window with the maintainability index, cyclomatic complexity, depth of inheritance, class coupling and lines of code of every
  namespace, type and member, as `Analyze | Calculate Code Metrics` of Visual Studio. The maintainability index carries its green / yellow
  / red band; double-click opens the member; the toolbar recalculates and copies the rows as CSV. Measured by a Roslyn helper built on the
  machine (no build of the solution is run — references are its last build)

## 0.1.1

- Debugger: long, multi-line and structured strings get a **View** link; the popup shows JSON, XML, HTML and JWT formatted and highlighted,
  the hover over such a string in the editor shows the same popup
- Debugger: temporary breakpoints (Remove once hit) and dependent ones (Disable until hitting the following breakpoint) work

## 0.1.0

The first public baseline.

- Solution view as in Rider: `.sln` / `.slnx`, solution folders, projects, dependencies, file nesting, Show All Files, VCS colors
- C# through `roslyn-language-server`: completion, diagnostics, quick fixes, rename, navigation, code lens, inlay hints, semantic highlighting
- Build, Rebuild, Clean, Restore in the Build tool window; Debug / Release and target framework switcher
- Run configurations from `launchSettings.json`, Endpoints window, `.http` requests
- Debugger on its own DAP client: breakpoints with conditions, hit counts and logpoints, exception breakpoints, Evaluate, Set Value,
  Attach to Process, debugging of tests, Set Next Statement
- Unit Tests explorer, code coverage (coverlet)
- NuGet window: search, install, sources, folders
- EF Core: migrations, database update, scripts, scaffolding, migrations window
- MSBuild files: schema-driven completion and documentation for properties, items and metadata
- Monitor, memory dump, allocations by line; Upgrade Assistant analysis; `dotnet new` templates with options
- Settings pages in English and Russian
