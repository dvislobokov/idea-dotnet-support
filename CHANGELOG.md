# Changelog

Every feature is a new version `0.1.x`. The build puts these sections into the change notes of the plugin
(Settings | Plugins → What's New) and fails when there is no section for the current `pluginVersion`.

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
