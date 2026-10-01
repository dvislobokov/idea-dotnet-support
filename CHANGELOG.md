# Changelog

Every feature is a new version `0.1.x`. The build puts these sections into the change notes of the plugin
(Settings | Plugins → What's New) and fails when there is no section for the current `pluginVersion`.

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
