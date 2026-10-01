# Changelog

Every feature is a new version `0.1.x`. The build puts these sections into the change notes of the plugin
(Settings | Plugins → What's New) and fails when there is no section for the current `pluginVersion`.

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
