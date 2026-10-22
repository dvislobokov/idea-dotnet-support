# Changelog

Every feature is a new version `0.1.x`. The build puts these sections into the change notes of the plugin
(Settings | Plugins → What's New) and fails when there is no section for the current `pluginVersion`.

## 0.1.298

- The code of the GIGA IDE editions is removed: the plugin no longer looks at GIGA IDE Pro at all (it has been fully available since
  0.1.293).

## 0.1.297

- IDEs with an older Kotlin library (GIGA IDE 2026.1): project files, the EF Core window, the index of assemblies and the analysis of C#
  no longer fail with `NoSuchMethodError: sequenceOf`.
- No more "JreHiDpiUtil: Must be not computed before that call" at the start of the IDE.
- When the system does not allow running a tool (a file without the execute permission, or a folder mounted with `noexec`, as
  `~/.dotnet/tools` on some managed Linux machines), the plugin says so and what to check, instead of a bare "Cannot run program".

## 0.1.296

- Razor: the highlighting of a `.cshtml` file could hang forever when several passes read the same tags at once; fixed.
- Build: without a local IDE the build takes the public IntelliJ IDEA Community (`ideaIC`) from the IntelliJ repository instead of the
  unified IntelliJ IDEA; the repositories and the IDE are chosen in one place for all the subprojects.

## 0.1.295

- C#: completion offers the static members of a library type imported with `using static` (`using static System.Math;` → `Abs`); in
  Razor, `@rendermode Inter` → `InteractiveServer`.
- C#: no postfix templates (`.arg`, `.await`…) after `using X.` and `namespace X.`; no namespaces of other projects opened in the IDE.
- Razor: `@using Microsoft.` offers the namespaces of the project's assemblies (`AspNetCore`, `JSInterop`…) as in a `.cs` file.
- Razor: the mode of `@rendermode` is C# in the editor in .NET 8 / 9 projects too (completion, navigation).

## 0.1.294

- Razor: completion of the directives after `@` at the start of a line (`inject`, `code`, `page`, `using`, `layout`...) and of the
  statements (`if`, `foreach`...); names for the property of an `@inject` (`IJSRuntime` → `JSRuntime`, `Runtime`).
- Razor: `@using`, `@implements`, `@inherits`, `@attribute`, `@layout` and `@rendermode` written after an `@inject` have completion too.
- Razor: the generator's own members (`__builder`, `BuildRenderTree`) are no longer offered.

## 0.1.293

- The plugin is fully available in GIGA IDE without Pro: the allocation profiler and every chart of the .NET Monitor work in any mode.

## 0.1.292

- macOS: the New Solution dialog no longer asks for the data of other apps. Its `dotnet new list` / `--help` ran in the working
  directory of the IDE — the home folder on macOS — where `dotnet new` looks for a project to check the constraints of the templates,
  and a stray `~/<user>.csproj` was evaluated with its globs over the whole home. The plugin's own `dotnet` commands without a folder
  of their own run in the temporary directory now.

## 0.1.291

- C#: no IDE error when the caret stands at the very start of a file whose first line is empty ("Add a partial part" took the line
  of the caret as (1, 0)).

## 0.1.290

- macOS: no more "would like to access data from other apps" prompts. A project file in the home folder (`dotnet new console` once
  run in `~` leaves `~/<user>.csproj`) was taken as the project of the plugin's own files under `~/Library/Caches`, and MSBuild's
  globs over that project walked the whole home, the data of every app under `~/Library/Containers` included. A project in the home
  folder or above it now owns no file by the folders a file is in, and the design-time cache never walks the home folder.
- The plugin's .NET helpers end with the IDE: a request that would not stop (such an evaluation) kept the helper and its
  `dotnet msbuild` running for hours after the IDE was closed. When the IDE goes, a helper cancels what runs and exits within seconds,
  killing the processes it started.
- macOS, Linux: the cache of design-time builds watches the sources a project takes from outside its folder: their absolute paths
  (`/Users/me/Shared/X.cs`) were read as options of the compiler, and a new file next to them did not load the project again.

## 0.1.289

- Razor: the C# of `@code` and of the markup is checked and completed again in a project the generator has run for (that is, almost
  always): the generator's output of the file was taken for a second part of its class, every member of `@code` was there twice, and
  the semantics gave up — no errors in `@code` (`Navigation.Nope`, `count = true`), no members after `Navigation.`.
- C#: completion and "Import type" offer the types of the projects the file's project sees only — its own and the ones it references; the
  types of every project of the solution were offered (a `Counter` of a project not referenced, with a `using` the compiler would not find).
- C#: a member declared verbatim (`public const string @Required`, as the resx source generator writes every resource) is found by its
  name — each use was a false CS0117 once the generators had run.
- C#: the constraints of a generic partial type written in one part hold in all its parts (`where T : struct` in a component's `.razor.cs`
  and `T?` in its `.razor`: no false CS1503 "cannot convert from 'T' to 'T?'").
- Razor: the C# of a view its project leaves out (`<Content Remove="Plugins\**">`, the plugin views nopCommerce copies into `Nop.Web`) is
  not checked, and the Solution view shows such Razor files as excluded.
- Razor: an `@inject` between a code block and the markup no longer hides the code block's variables from the markup after it.

## 0.1.288

- Razor: inlay hints of C# in the markup and in `@code` — parameter names and types, by the C# options (Settings | Editor | Inlay Hints |
  Razor); none in the code the Razor generator adds around the markup.
- Razor: an edit of a component (saved or not) updates its class for the C# of the project about a second later — a new `[Parameter]` is
  completed and found by Ctrl+B in `.cs` files without waiting for the generator.

## 0.1.287

- Debugger: breakpoints in lambdas — on a line with lambdas the gutter offers the line or one of them (`.Where(n => n % 2 == 0)`), and the
  breakpoint stops inside that lambda only.
- Debugger: function breakpoints — "+" in the Breakpoints dialog adds ".NET Function Breakpoint" by the name of a method
  (`Namespace.Class.Method`, `Class.Method` or `Method`), with a condition and a hit count.
- Debugger: the Modules tab of the debug window lists the loaded assemblies, their paths and whether their symbols are loaded.
- Debugger: Debug of tests on Microsoft.Testing.Platform (MSTest runner, xunit.v3, TUnit) stops at their breakpoints; before, the tests ran
  without the debugger. xunit.v3 projects with `TestingPlatformDotnetTestSupport` are recognized as Microsoft.Testing.Platform projects.

## 0.1.286

- Razor: Find Usages of a member of a component's `@code` started in a `.cs` file also finds its uses in the `@code` and the markup of the
  component.
- Razor: the usages lens of a component, a tag helper and their parameters counts their tags and attributes in the markup
  ("1 usage, 2 usages in markup").
- Razor: the completion list opens by itself while the C# value of an attribute is typed (`asp-for="Na"`).
- Razor: the name of a component's attribute is no longer also a C# usage of its parameter (Find Usages listed each attribute twice).
- Razor: view components in `.cshtml` — `<vc:` completes their tags and their parameters, Ctrl+B goes to the class.
- Razor: the tags of components, view components and tag helpers and the attributes setting their parameters are bold, in the colors
  of a class and a property (Settings | Editor | Color Scheme | Razor).
- Razor: the completion list offers each tag and attribute once (no second `embed`, `form` or `count` without its type).
- Razor: Find Usages of a dictionary property of a tag helper finds the attributes of its prefix (`asp-route-id` of `RouteValues`).

## 0.1.285

- Razor: line breakpoints in `.razor` and `.cshtml` files — on an expression of the markup (`@count`), a statement (`@if`, `@foreach`,
  `@{ }`) and the code of `@code`; the debugger stops there, shows the frame in the file and evaluates in the component.
- Debugger: a stop the IDE could not show is logged and shown as a pause instead of leaving the program stopped unseen.

## 0.1.284

- Razor: Go to Related Symbol (Ctrl+Alt+Home) between a component's `.razor`, `.razor.cs`, `.razor.css`, `.razor.js` and a page's
  `.cshtml` and `.cshtml.cs`.
- Razor: the routes of components (`@page "/counter"`) and Razor Pages (their path under `Pages/`) in the Endpoints tool window.
- Razor: Alt+Enter on an element named like a component of a namespace the file does not import — "Add @using …".
- Razor: Refactor → Extract Component moves the selected markup into a new component beside the file and puts its tag in its place;
  one Ctrl+Z takes both back.

## 0.1.283

- Razor: Enter indents the new line by the C# blocks and the elements around it — one level in `@code { }`, `@{ }`, `@if (…) { }`,
  a method of `@code`, an element of the markup.
- Razor: Reformat Code indents the lines of a `.razor` / `.cshtml` file the same way (comments, `<pre>`, `<script>` and `<style>` are
  kept; the children of `html` and `body` follow Code Style | HTML, "Do not indent children of").

## 0.1.282

- Razor: the errors and warnings of the Razor compiler in `.razor` and `.cshtml` files as you type — an unclosed block (RZ1006), an
  unknown component (RZ10012), a component without its `[EditorRequired]` parameter (RZ2012), the errors of directives, sections and
  tag helpers — with the compiler's ids, messages and places.
- Opening a `.razor` (or any file) of a project starts its source generators: the C# in the markup reports its errors without a `.cs`
  of the project opened first.

## 0.1.281

- Razor views (`.cshtml`): the tag helpers of MVC (`asp-action`, `asp-for`, …) and of the project, in scope by `@addTagHelper` — completion
  of their tags and attributes, Go to Declaration to the tag helper's class and property (MVC's in the view of the assembly), Find Usages
  of a tag helper's class or property lists its tags and attributes in the views (Alt+F7 on a tag or an attribute too), and the rename of
  a property writes its attributes (`MailTo` → `mail-to`); an attribute named by `[HtmlAttributeName]` keeps its name.
- The HTML inspections no longer report the tags and attributes a tag helper binds as unknown ("Unknown html tag email", "Attribute
  tooltip is not allowed here"), an `<input asp-for>` as lacking its label, or `@bind:event` as an unbound namespace prefix.
- The value of a tag helper's attribute the compiler reads as C# (`asp-for="Name"`: `__model => __model.Name`) is C# in the editor:
  completion of the model's members, navigation, errors. A Razor file open before the tag helpers of its project were found no longer
  waits for an edit to get their C#.

## 0.1.280

- Razor: a renamed, new or moved component has its class at once — the plugin writes what the SDK's Razor generator makes of it with
  its own port of the generator, where the generated files are, and removes the old one; the C# naming the new name no longer waits
  for the generators (as a renamed Unity script).
- The helper of generators and analyzers lists the files of a project again in place when a file appears, goes or is renamed: the
  projects referencing it stay loaded and the generators keep their state. A renamed file of MudBlazor: 15.7 s to fresh generated files
  before, 3.7–5.1 s now.

## 0.1.279

- Unity: no Build and Run for a Unity solution — Unity compiles the scripts and runs the game in its editor. Build / Rebuild / Clean,
  Build Performance, Run MSBuild Target, Publish and Run / Debug Project are off in the .NET menu (with the reason) and gone from the
  context menus; the Build Solution button is not on the toolbar; no `dotnet run` / `dotnet test` configuration is made from a Unity
  project (its tests run in the editor through "Unity Tests").

## 0.1.278

- Unity 2023: the editor package (1.4.2) compiles with the Test Framework of Unity 2023 (1.3.9), where cancelling a test run is not a
  public API — before, the package broke the compilation of the whole project there.
- Unity: Attach to Unity Editor no longer waits forever after switching the editor to Debug mode (the end of the compilation was lost
  with the reload of the scripts).
- Unity: the IDE does not put its own editor package over a newer one in the project (an older IDE or plugin used to downgrade it).
- Unity debugger: enums show the name of their value, generic types read as C# writes them (`List<string>`), and static properties of
  structs (`Vector3.zero`) are in the "Static members" group instead of failing among the instance ones.

## 0.1.277

- Unity debugger: evaluation in the runtime — Evaluate, watches and the hover take C# expressions: properties (`transform.position`,
  `gameObject.activeSelf`), method calls with arguments (`_levelScene.ToUpper()`, `HasArg("-host")`, `Mathf.Max(2, 7)`), static members
  of a type by its name (`Time.frameCount`, `Application.isPlaying`, `string.IsNullOrEmpty(name)`), indexers (`args[i]`, `list[0]`),
  operators of numbers, strings and of Unity's structs (`Vector3.one * 2`), `?:`, `&&`, `||`. The calls run on the thread of the stop with
  breakpoints off and are aborted after 5 s; an exception they throw is shown with its message.
- The line of an object's value shows its `ToString()` when its type overrides it (`GameBootstrap (Game.Boot.GameBootstrap)`,
  `(0.00, 0.00, 0.00)` of a Vector3), and objects list their properties, read when shown — both only with "Allow property evaluation and
  other implicit function calls" on (Settings | .NET | Debugger). Properties of Unity that copy what they return (`material`, `mesh`) or
  only throw are not read by themselves.

## 0.1.276

- Unity debugger: the variables of a frame — `this`, the parameters, the locals alive at the stop (and the locals of iterators, async
  methods and lambdas, which the compiler keeps in fields); the fields of objects and structs with their base classes', static members
  as a group, the elements of arrays, strings, enums by name, a small struct of numbers in one line (`{x = 1, y = 2, z = 3}`). Evaluate,
  watches and the hover in the editor take names of variables and fields (`_session.State`, `args.Length`); other expressions are not
  evaluated yet.

## 0.1.275

- Unity: a debugger for the scripts in the open Unity editor — .NET → Attach to Unity Editor (also next to Play / Pause / Step in the
  toolbar) switches the editor to Debug mode first when it is in Release (with your consent, for this session), then attaches. Line
  breakpoints in `.cs` files, also in code Unity loads later and again after every compilation of the scripts; the threads and their
  frames with the source line; Pause, Resume, Step Over / Into / Out, Run to Cursor; Stop detaches and the editor goes on. The plugin's
  own client of the Mono debugger protocol: nothing to install. Variables, conditions and hit counts are not supported yet.

## 0.1.274

- Unity: .NET → Switch Unity to Debug Mode / Release Mode switches the code optimization of the open Unity editor for this session, as
  the bug button of Unity's status bar does, without switching to it (the editor package 1.4.0); the tooltip of the Unity status bar
  widget names the mode. Debug mode is what a debugger needs: attaching to an editor in Release mode stops it at a modal dialog.
- The first part of a debugger for Unity: the plugin's own client of the Mono soft debugger protocol (no adapter to install, no `dotnet`
  needed), not yet reachable from the UI.

## 0.1.273

- Unity: tests of the Unity Test Framework run in the open Unity editor from the IDE — ▶ next to a test method or class (`[Test]`,
  `[TestCase]`, `[UnityTest]`) in a Unity project runs it there, in edit mode or play mode as its assembly definition says; the results
  come into the Unit Tests window as they are made, with the message, the stack and the output of a failed test. Stop cancels the run.
  A saved run configuration "Unity Tests" takes a mode and filters.

## 0.1.272


- Unity: the IDE can be Unity's external script editor — Preferences | External Tools of Unity lists it, and the IDE offers it once
  (Open Scripts from Unity in This IDE; Stop Opening Scripts from Unity Here gives Unity back the editor it had). A double click on a
  script or on a line of Unity's console then opens it in the IDE at the line, or starts the IDE when it is closed. Unity keeps
  generating the projects: with the generator of its Rider or Visual Studio package.

## 0.1.271


- Unity: the window **Unity** shows the log of the editor's console — messages, warnings, errors and exceptions with their stack, the errors
  of the compilation with their code and place. Double-click or Enter opens the line of the code; the places of the stack are links. Filters
  by severity with counts, Collapse, search, Clear (the errors of the last compilation stay, as in Unity) and Clear on Play. Open it from
  the stripe, the .NET menu (Show Unity Log) or the status bar.
- Unity: a new version of the editor package is taken by the open editor at once, without switching to it.

## 0.1.270


- Unity: the IDE links with the open Unity editor. Play, Pause, Step and Stop the game from the IDE (the .NET menu, the main toolbar, the
  status bar); a script saved in the IDE is compiled by Unity at once, without switching to it. The link is a small package the IDE puts
  into `Packages/` of the Unity project when you agree (hidden from git; Unity notes it in `Packages/packages-lock.json`), and updates.
  The status bar shows what the editor does: edit mode, playing, paused, compiling.
- Unity: a script renamed or moved in the IDE stays in the Solution view at its new name at once (it was missing for seconds, until Unity
  wrote its projects again and the IDE noticed); the sources of Unity's packages in `Library/PackageCache` belong to no project (they took
  the first project of the folder and its `#if` symbols, and the opening of a project raised IDE errors "PSI and index do not match").
- Unity: NuGet is not available for Unity projects — the NuGet window says why (Unity generates the projects and restores no packages;
  its packages come from the Package Manager), the NuGet menu is off and packages are not restored automatically.
- C#: the `#if` symbols and the language version of a file are kept between sessions of the IDE, so the first parse after a restart is
  the one the index has (IDE errors "PSI and index do not match" at the opening of a project whose files changed their symbols).
- The indexer of assemblies no longer fails on an attribute with a `typeof` argument of `null` (an assembly of Unity's packages).


## 0.1.269


- Source generators: a renamed, new, deleted or moved `.razor` reaches the generators at once — a renamed component got no class until
  the solution was opened again, and the C# naming it stayed red.
- Source generators and analyzers use much less memory: the projects of a solution share one copy of the referenced assemblies, and a
  project loaded again (a renamed file, an edited project file) no longer leaves its old copy behind — the helper of a solution of ten
  projects grew from 590 to 870 MB in a few renames, it stays near 450 MB now.

## 0.1.268


- Razor: Find Usages and rename of a component found no C# naming its class when the class was the build's (`typeof(Counter)` in a
  `.cs` file) — now they are found and renamed.
- Razor: Shift+F6 on a use of a parameter inside `@code` said "is not a symbol the built-in rename knows" once the project was built,
  and on a name inside `@code` it could end in an IDE error — the rename of the C# in a Razor file asks the new name in a dialog now.
- Razor: opening a component reported an IDE error ("Range must be inside file offsets") — the gutter marked a method the file does not
  write.



## 0.1.267


- Razor: Find Usages of a component — on its tag, on its `.razor` file or on its C# class — lists its tags in the markup and the C# that
  names it; Find Usages of a parameter — on the attribute or on the property — lists the attributes setting it (`Step="2"`,
  `@bind-Value`), its child content tags and its uses in C#, in `.cs` files and in the C# of the markup.
- Razor: Shift+F6 on the tag of a component renames the component: its `.razor` file with the files nested under it, the tags, the class
  of the code-behind and the C# that names it. On the attribute of a parameter it renames the parameter with every attribute and use.
  The rename of a component's class or of a parameter's property in C# writes the markup too.
- C#: Find Usages and rename find the uses in the C# of Razor files (`@code`, `@Model.Name`, the expressions of the markup).

## 0.1.266


- Razor: Ctrl+Space pressed again over the open list of tags kept saying "No suggestions" — the components stay in the list now.
- Razor: the types beside the attributes in completion read as in code, `EventCallback<MouseEventArgs>` instead of `MouseEventArgs>`.

## 0.1.265

- Razor: completion of components in the markup. After `<` the components in scope are offered by their names (the others by the full
  name), inside an element of a component its parameters come first, then `@bind-…`, `@onclick`, `@ref`, `@key` and the rest that
  applies to it.
- Razor: Go to Declaration (Ctrl+B, Ctrl+click) from the tag of a component opens its `.razor` file or its C# class (a component of a
  package — its type in the assembly view), from the name of an attribute — the parameter.

## 0.1.264

- Razor: the components and tag helpers are known as the compiler knows them. In a `.razor` file an element of a component
  (`<Counter Step="@step" CountChanged="OnCount" />`) is the component: its attributes are its parameters in C#, with completion and
  Go to Declaration in their values; `@onclick`, `@bind`, `@bind-Value`, `@ref`, `@key` and MVC's tag helpers in `.cshtml` are C# too.
  The components come from the project's `.razor` and `.cs` files (a new `[Parameter]` is known a moment after it is typed, no build
  needed), from the projects it references and from its packages.
- The index of assemblies keeps the arguments of attributes (format 7): the indexes are made again once after the update.

## 0.1.263

- The page about the plugin (the Welcome tab) has a section on Unity: the project as in Rider, the scripts that know their scenes,
  prefabs and animations, the hints and inspections of Unity; Unity in the comparison with Rider.

## 0.1.262

- The C# language server (roslyn-language-server) is gone from the plugin: its client, the Language Server settings page, the
  switches between the server and the built-in features, the server's status widget and banners. Every C# feature is the plugin's own
  and needs neither the server nor .NET 10 to run it.
- The options of the editor (analysis scopes, completion, navigation, Code Lens, inlay hints, editing, code generation) are on
  Settings | .NET | C# Editor; the values set before are kept.
- Settings | .NET | Toolset and Build no longer lists roslyn-language-server among the tools; .NET → Probe Platform DAP API probes the DAP
  module only.

## 0.1.261

- GIGA IDE editions: in GIGA IDE without Pro the allocation profiler ("Show Allocations in Editor") is off and the .NET Monitor shows
  CPU, Memory, Time in GC and GC collections; the other charts come with Pro ("Activate PRO mode" right in the window), without a
  restart. Outside GIGA IDE nothing changes.

## 0.1.260

- Unity scenes, prefabs and assets, as Rider reads them (an index of their text, "Force Text" serialization):
  - the usages lens says where the assets use the code: "Used in Boot.unity" over a script, "Event handler in Menu.prefab" over a
    method a UnityEvent calls, "Animation event in Walk.anim" over a method an animation calls, "Set by Unity: 5" over a field the
    inspector set;
  - Find Usages of a script, a method or a serialized field lists its uses in the scenes, prefabs and animations;
  - scenes, prefabs, assets and `.meta` files open as YAML (needs the bundled YAML plugin): Go to Declaration from a `guid` to the
    script or asset, from `m_MethodName` to the method; a component whose script is gone is reported ("Missing script");
  - renaming a serialized field whose value an asset stores adds `[FormerlySerializedAs("oldName")]`, so the value survives.
- IntelliJ IDEA: the module the plugin makes for a folder with a solution is no longer lost when the project model is synced from `.idea`
  a few seconds after the start (an IDE closed without saving, then reopened): the files of the solution stopped being indexed, and
  navigation, Find Usages and the inspections of non-C# files went quiet five minutes after opening.

## 0.1.259

- Unity assembly definitions: `.asmdef` and `.asmref` open as JSON, `.asmdef` with the schema of Unity Assembly Definition (the keys
  completed, their meaning in Quick Documentation, wrong types reported). The names in `"references"` (`"reference"` of an `.asmref`)
  are links to the assembly definitions of the project, by name or by `"GUID:…"` of the `.meta`: completion, Go to Declaration,
  and a warning on one the project does not have (Settings | Editor | Inspections | C# | Unity). Needs the bundled JSON plugin.

## 0.1.258

- Unity extras (UNITY_PLAN.md), as Rider has them, in Unity projects only:
  - inspections with quick-fixes (Settings | Editor | Inspections | C# | Unity): `?.` / `??` on a Unity object (a destroyed object is
    not null to them); `tag == "…"` → `CompareTag`; a redundant `[SerializeField]` on a static, const or readonly field; an event
    function in the wrong case (`update`) or with parameters of the wrong type (`OnTriggerEnter(Collision)`); `GetComponent`, `Find`,
    `SendMessage` in `Update` / `FixedUpdate` / `LateUpdate` / `OnGUI` — "Introduce a field and initialize it in 'Awake'"; `GetComponent<T>`
    of a `T` that is not a component (an `ArgumentException` at run time); a tag, layer, scene or input axis the project does not define;
  - the Unity icon in the gutter of event functions, "called every frame" on the frame methods;
  - completion of the tags, layers, scenes and input axes of `ProjectSettings` in `CompareTag("`, `LayerMask.NameToLayer("`,
    `SceneManager.LoadScene("`, `Input.GetAxis("`; the components of the project and of Unity first in `GetComponent<`;
  - the `.meta` of a file follows it on Rename and Move, so the scenes keep their references to the script;
  - live templates `sfield`, `coroutine`, `menuitem`, `header`.

## 0.1.257

- Unity (UNITY_PLAN.md, step 3): the event functions by completion, as Rider writes them — in the body of a `MonoBehaviour` (and of a
  `ScriptableObject`, `Editor`, `EditorWindow`, `AssetPostprocessor`), `OnTrigEn` offers `OnTriggerEnter(Collider other)` and the other
  messages the class does not declare yet; Enter writes the method with an empty body, the caret inside (`private`, or the access
  modifier typed before the name).
- Unity: only the solution in the root of a Unity project is offered — a package's own `.sln` in `Assets` (FishNet's `Mono.Cecil.sln`)
  no longer brings "Several solutions" and the chooser. The namespace of a folder takes the `rootNamespace` of its assembly definition.

## 0.1.256

- Unity (UNITY_PLAN.md, step 2): what the engine uses without code no longer reads "no usages" — "Event function" over the messages of
  `MonoBehaviour`, `ScriptableObject`, `Editor`, `EditorWindow`, `AssetPostprocessor` (`Update`, `OnTriggerEnter`...), "Set by Unity" over
  serialized fields (`[SerializeField]`, public fields of a component), "Called by Unity" over `[MenuItem]` / `[ContextMenu]` /
  `[RuntimeInitializeOnLoadMethod]` methods, "Unity script" over component classes. The namespace of a folder counts from its assembly
  definition (`Assets/Game/Boot` of `Game.Boot.asmdef` is `Game.Boot`, not `Game.Boot.Assets.Game.Boot`), and new files of a project with
  `LangVersion` below 10 get a block namespace.
- Opening a project whose `#if` symbols settle while its projects are evaluated (a Unity project of many assemblies) no longer raises
  "IDE error occurred" (a stub tree that does not match the file): the symbols of a file, its reindex and its new PSI now change together.

## 0.1.255

- Unity (UNITY_PLAN.md): a Unity project (`ProjectSettings/ProjectVersion.txt` and `Assets`) shows in the Solution view with the Unity
  icon and the editor's version, without `Library`, `Temp`, `Logs`, `obj`, `UserSettings` and the `.meta` files (Show .meta Files in
  the view options brings them back); the solutions inside `Library/PackageCache` are not offered as solutions. New → Unity after a
  separator: MonoBehaviour Script, ScriptableObject, Editor Script, Property Drawer, Editor Window, Assembly Definition. .NET →
  Open in Unity Editor starts the editor of the project's version installed by Unity Hub. A folder among several projects of one
  directory belongs to the project that has its files (Build of the folder builds that project).
- New: one level less — the categories of .NET items (C#, ASP.NET, Tests, Configuration, Other...) are submenus right under New, no
  ".NET" submenu in between; Unity is one of them.

## 0.1.254

- Unity projects open with their types (UNITY_PLAN.md): an absolute `HintPath` (Unity's generated projects name the editor's
  assemblies by their full path) was joined to the project folder and dropped, so nothing was indexed; and a folder holding several
  project files (Unity keeps one per assembly definition in the root) always picked the first one — the Solution view showed another
  project's files under a project, the editor said a file was not a part of the wrong project. The project of a file is now the one
  whose `Compile` items list it; the Solution view takes the project of the node above.

## 0.1.253

- Razor: the virtual C# document of a `.razor` / `.cshtml` file is now the C# of the SDK's source generator (RAZOR_PLAN.md, R3b): the
  compiler's code generation pipeline — the intermediate tree, the directive and component passes, tag helper resolution, the runtime
  writer with its `#line` pragmas and source mappings — is ported to Kotlin in `razor-psi-core` and generates byte for byte what the
  compiler generates (its 93 file-based baselines, eShop, MudBlazor and nopCommerce against the SDK). The document follows the project:
  `_Imports.razor` / `_ViewImports.cshtml` up the folders, the root namespace, the Razor version of the target framework; the members of
  `@code` and `@inject` sit at their place in the file. Tag helper and component descriptors come as data (`TagHelperDescriptorJson`);
  the project's own come with R5.

## 0.1.252

- Razor: the C# of a `.razor` / `.cshtml` file is one virtual C# document injected into the page (RAZOR_PLAN.md, R3a): completion
  after `@` and `@Model.`, Go to Declaration from the markup to `@code` and to the `.cs` files of the project, the C# errors of the
  plugin's analysis in the markup and in `@code`. The Razor tree in the editor is now the port of the compiler's parser (0.1.251);
  folding, the structure view and Ctrl+/ follow it.

## 0.1.251

- Groundwork for the Razor editor (RAZOR_PLAN.md, R2): a port of the Razor compiler's parser (`razor-psi-core`), checked against
  the compiler's own parser tests and against the installed SDK's compiler on the corpus of three solutions. Nothing visible yet:
  the editor still runs on the lexer of 0.1.229.

## 0.1.250

- The overloads of a base class stay candidates beside the derived class's methods of the same name (`Add(T)` of the base under
  `Add(T, int)` of the derived); an override with the same parameters is one method. A fluent chain like MudBlazor's
  `From<…>(…).Then(s => …)` types the lambda's parameter again.
- A method group of an extension method (`obj.Extension` passed where a delegate or a `T` is expected — Moq's
  `ReturnsAsync(obj.Method)`) has its function type, with the receiver bound.
- An async lambda whose body names a member one candidate's parameter type lacks binds with the other candidates only.
- Which overload is more specific is judged by the parameter types as declared (`Equals(T other)` of a `Struct<object>`
  loses to its `Equals(object)` override), as the compiler does.

## 0.1.249

- A library type whose internal interface the index does not hold (`ImmutableArray<T>` and its `IImmutableArray`) still decides
  what it does not convert to (`Assert.That(immutableArray, constraint)`).
- The parameter of a lambda passed to an overloaded method is typed by the candidates that can take a lambda at all — an overload
  taking an interface there no longer leaves it untyped.

## 0.1.248

- An extension method on `int?` / `DateTime?` is found for a receiver of that type (`MaxHeight.ToPx()`, `End.ToIsoDateString()`).
- `value` of a setter is typed inside a lambda of the setter too; `!x` of a type converting to `bool` is a `bool`; a collection
  expression does not convert to a class or struct that is no collection; an interpolation holding `c ? a : b` of non-constants is
  not a constant.

## 0.1.247

- A lambda whose body names a member one candidate's parameter type does not have (`args => !args.Value ? … : …` against
  `Func<ParameterChangedContext, Task>`) binds with the other candidates only, even when the body has a type of its own.
- A generic method group with its type arguments written (`Mock.Of<T>` passed to Moq's `ReturnsAsync`) has the signature of
  that instantiation: nothing is inferred from the delegate.

## 0.1.246

- A project reference with `<Aliases>` (`extern alias X;` in the code) is out of the global namespace: its types are reached
  as `X::N.T` (or `X.N.T`) only, so a referenced project whose namespaces match the code's own does not make every name
  ambiguous (MudBlazor's analyzer tests). An `extern alias X;` written inside a namespace declaration counts too.

## 0.1.245

- `using static T;` (and a project's `<Using Include="T" Static="True" />`) brings the extension methods of `T`, as the
  compiler's lookup does: MudBlazor's `x.ToStr()` in every chart resolves.
- No "unnecessary using directive" in generated files (`obj/…/generated`), as the compiler hides them there.

## 0.1.244

- A lambda whose body does not bind with a candidate's parameter types (`item.StartsWith(…)` with `item` an `object` of that
  delegate) is no conversion to it, as the compiler judges: the candidate drops out instead of staying "not known".
- Beside an applicable method of a derived class, the methods of its base classes are no candidates (§12.6.4.1 —
  FluentAssertions' `Should().Be(null)` on a `Guid?`).
- A collection expression converts to `ImmutableArray<T>` and the other immutable collections, not to an `Expression<…>`.
- The analysis debug log no longer reports the lambdas of a candidate another argument already rules out.

## 0.1.243

- The solution bounds what a project sees: when its references cannot be read from the project file (`$(SomeProperty)\…`,
  a `Directory.Build.props` adding references), every project of the active solution is visible and none outside it;
  `$(SolutionDir)` in a `ProjectReference` is the folder of the active solution (nopCommerce's plugins).
- The platform's "C# solution build scripts found" notification listing every solution of the folder is gone — the plugin's
  own chooser does that.

## 0.1.242

- Open Solution, as Rider: a `.sln` / `.slnx` / `.slnf` opens as the project (File | Open, the welcome screen, a file dropped on the
  window) — its folder becomes the project and that solution the one the plugin works in. A folder with several solutions asks
  which one on start; the Solution view, Build, NuGet, Unit Tests and the C# language server all work in the one chosen.
  .NET | Select Solution… changes it; the choice is kept with the project. The language server's own choice moves to it.
- A solution folder is drawn lilac, as in Rider.

## 0.1.241

- `using X;` of a namespace declared only by a project this one does not reference is CS0246, as the compiler says (the test of
  0.1.240 expected the old silence; nothing else changes).

## 0.1.240

- Extension methods are looked up level by level, as the compiler does: the innermost namespace (with its usings) that has an
  applicable candidate wins — nopCommerce's own `CountAsync` in `System.Linq` beside LinqToDB's, `AddHttpContextAccessor`
  beside ASP.NET Core's, and its `OrderBy(this IQueryable<Product>, …)` no longer shadows System.Linq's for other queries.
- `list.Count(x => …)` is Enumerable's: a property of a non-delegate type is not invocable. A generic method group
  (`Task.FromResult`) converts to a delegate by its inferred type arguments.
- A generic overload calling its sibling with its own type parameter (`GetAsync<T>(key, () => Task.FromResult(acquire()))`)
  resolves; a lambda does not convert to a type parameter; `() => throw …` is compared by its targets.
- `where TKey : notnull` holds; `unmanaged` for primitives and enums; `allows ref struct` is no constraint.
- `DayOfWeek - DayOfWeek` is an `int`; a switch expression of tuples differing in names only is typed; a generic local
  function with type arguments written is typed; `out var x` of a local function is typed; a collection expression does not
  convert to an enum of the solution.
- A namespace of the solution counts only when a file of a project this one sees declares it (another plugin's
  `Nop.Plugin.Shipping` was the `Shipping` type of the PayPal plugin).

## 0.1.239

- The returns of a lambda take their best common type (`IEnumerable<X>` over `List<X>`); `return null` / `default` / a tuple
  with such an element is typed by the others. A method group named as a value has its function type, not what it returns.
- `b ? [p] : parts` and `f ?? (x => …)`: a target-typed operand takes the other's type. The members of `object` are found
  through an interface of the solution (`c.GetType()`).
- An indexer is found by its kind, whatever its `[IndexerName]` (`XmlAttributeCollection`); a library property that
  overrides one accessor only reads and writes through the other of its base (`XmlAttribute.InnerText`).
- The `notnull` constraint holds (a warning in Roslyn), `unmanaged` for primitives and enums; overload betterness does not
  ask the type of a `default` / `null` / `new()` argument (a cycle); an interpolated string with a conditional access, an
  array, a `typeof`, a tuple or a collection in a hole is not constant.

## 0.1.238

- A `file` type (C# 11) is seen in its own file only: the same `file static class` written by a source generator into every project is no
  longer one type with twice its members.
- The parts of a partial method match by their resolved parameter types, however they are written (`ILogger` beside
  `global::Microsoft.Extensions.Logging.ILogger` of LoggerMessage, NSwag and OpenAPI generated code).
- `extern alias X;` — a name written `X::Name` resolves.
- A lambda returning `throw …`, `null` or `default` gives the inference no type instead of stopping it.
- An `internal` extension class of another project that does not make this one a friend is no candidate (eShop links
  `Shared/ActivityExtensions.cs` into every project).
- Type arguments written on a call close the candidates' type parameters: an overload calling its sibling with its own
  type parameter (`services.AddMigration<TContext>((_, _) => …)`) resolves.
- The discard `out _` gives the inference nothing; `x is { } y` over a `T?` types `y` as `T`.
- Overload betterness no longer asks the type of an `out var x` argument (the call was asking itself).
- Every place the lambda conversion check gives up writes its reason to the analysis debug log; an async lambda does not
  convert to a delegate returning a type that is not a task (CS4010 prevents it).

## 0.1.237

- A lambda written as the body of another lambda (`a => s => …`, Blazor's `RenderFragment<T>` templates in generated code) has its
  parameters typed by what the outer delegate returns.
- The declaration and the implementation of a partial method are one method: a call to it resolves and has its return type (generated
  code of LoggerMessage, NSwag, OpenAPI comments).

## 0.1.236

- Anonymous types: `new { Id = x.Id, Name }` has a type with these properties — completion and navigation on `a.Id`, the type of a LINQ
  projection (`Select(x => new { x.Id, Count = … })`) and of what follows it (`.ToList()`, `.Where(…)`), and an assignment of a property to
  the wrong type is CS0029. The same names and types in the same order are one type, as in the compiler; it is shown as
  `<anonymous type: int Id, string Name>`.

## 0.1.235

- `value` inside a `set`, `init`, `add` or `remove` accessor has the type of its property, indexer or event: completion on it, and an
  assignment of it to the wrong type is CS0029.
- A lambda with typed parameters (or none) has its natural type, as in C# 10: `var f = (string s) => s.Length;` is a `Func<string, int>`
  and `f("a")` an `int`.
- `out _` fits any `out` parameter when the overload is chosen; a collection expression `[…]` passed beside an overload taking a delegate
  no longer stops the inference of the call.

## 0.1.234

- The type arguments of a generic call and the overload a call goes to come from the compiler's rules alone: the older guessing
  (unifying argument types, scoring candidates, refining by lambda bodies) is removed. A call whose type the compiler's inference cannot
  fix has no type now instead of a guessed one. When no overload applies, the call goes to the one whose lambda binds to its delegate —
  the candidate the compiler reports (`Select(x => { if (…) return 1; })` is the `Func<T, R>` overload with CS1643).

## 0.1.233

- A method group passed for a delegate is resolved as the compiler resolves it: the method is chosen by the delegate's parameter types
  among the overloads (a generic one infers its type arguments from them), by the return type, and by the stricter delegate
  compatibility; local functions count. A lambda with typed parameters (or none) and a method group with one signature give their
  function type (`Func<…>` / `Action<…>`) to a type parameter of a generic method, as in C# 10 — `Create(this, () => { … })`,
  `DebounceAsync(Invoke)` resolve to one overload. A block lambda that returns nothing is no candidate for a delegate returning a value.

## 0.1.232

- Which of two applicable overloads is better is decided as the compiler decides it (the port of Roslyn's better function member):
  the exact match of a lambda by what it returns, `Queryable` over `Enumerable` for an `IQueryable` receiver, a delegate returning a value
  over a `void` one, the normal form over the expanded `params` one, a non-generic method over a generic one, the more specific
  parameter types, a signed integral type over an unsigned one, a value parameter over an `in` one. The older rules are removed.
- A `const string` in an interpolated string keeps it constant; a variable, a property or a call does not — the choice between
  `AppendLine(string)` and the handler overload is made for every interpolated string.

## 0.1.231

- The overload a call goes to is chosen as the compiler errors judge it: Roslyn's type inference with the lambdas, the ported
  conversions, the `new()` and `class` constraints. A lambda passed where an interface or another non-delegate type is expected is no
  candidate; an `async` lambda fits a delegate returning `Task`, `ValueTask` or `void`. bUnit's `InvokeAsync(() => …)` and
  nopCommerce's `SaveSettingAsync(settings, …)` now resolve to one overload.

## 0.1.230

- Razor files fold their `@code`, `@functions`, `@section`, `@{ }` blocks and multi-line `@* *@` comments, match Razor's braces,
  comment with Ctrl+/ (`@* *@` on markup and directives, `//` in code) and show their directives, sections and the members of
  `@code` in the Structure window.

## 0.1.229

- Razor files (`.razor` components and `.cshtml` views) open as their own language: the Razor transitions, directives and code blocks are
  coloured, the C# inside them with the colours of C#, the markup as HTML (tags, attributes, HTML folding). The colours are set on
  Settings | Editor | Color Scheme | Razor. HTML errors caused by the Razor next to them are not shown.

## 0.1.228

- The conversions of `null`, `default`, `throw`, `new()`, anonymous objects and interpolated strings in overload resolution follow
  Roslyn's rules (the port of its conversions from expressions); the older rules for them are removed.

## 0.1.227

- A method group passed where a `Delegate` (or `object`) is expected converts by its function type, as in C# 10: overloads such as
  `app.MapGet("/items", GetItems)` are picked as the compiler picks them, and the result type of the call is known. This is the first
  piece of the port of Roslyn's conversions from expressions.

## 0.1.226

- A method of a project referenced by another one is one candidate, not two: the IDE also reads the built output of the referenced project,
  and its copy of the method no longer stands beside the source one (as the compiler sees a project reference). Calls of such methods are
  resolved instead of left unknown (MudBlazor: 8 967 → 6 775, nopCommerce: 4 850 → 2 387 places where the analysis gave up).

## 0.1.225

- A method with a pointer parameter (`ArgumentNullException.ThrowIfNull(void*)` beside `ThrowIfNull(object?)`) is not a candidate for an
  argument that is no pointer, `null` or `default`, as in the compiler: `ThrowIfNull(x)` and similar calls are resolved instead of left
  unknown (nopCommerce: 7 558 → 4 850 places where the analysis gave up).

## 0.1.224

- An anonymous object passed to a method (`RedirectToRoute("Product", new { id })`, `Json(new { success = true })`) converts to an `object`
  parameter and to no class, as the compiler sees it: such calls are resolved instead of left unknown (nopCommerce: 10 337 → 7 558 places
  where the analysis gave up).

## 0.1.223

- The move of the plugin's data to the work directory under an execution policy (0.1.220) no longer gives up when
  `/home/work/<user>` is shared with a group or its owner is named in another form: it only has to exist and be writable; the plugin's own
  `.cache/dotnet-support` under it is made private (`rwx------`). The work directory is also found by what `/home/work` holds
  (`ivan@CORP.LOCAL`, `Ivan`). When none takes the data, the notification offers the one found, and the log (`plugin-data`) says why each
  candidate was not taken.

## 0.1.222

- An interpolated string passed to a method that takes an interpolated string handler (`StringBuilder.Append($"…")`,
  `AppendLine($"…")`, `Debug.Assert(…, $"…")`) is resolved as the compiler resolves it: a non-constant string goes to the handler
  overload, a constant one to the `string` overload. The analysis no longer gives up on such calls.

## 0.1.221

- Show All Files in the Solution tool window also shows the files and folders of the solution's directory that belong to no project —
  `README.md`, `.gitignore`, the `.sln` itself, `.idea`, folders of docs or scripts — greyed, beside the projects, as in Rider. A file
  added there appears without a reload.

## 0.1.220

- On Linux systems where programs may not run from the IDE's own folders (fapolicyd, a `noexec` mount, SELinux), the plugin keeps all
  its data — caches, indexes, the helpers it builds, generated files, logs, temporary files — in `/home/work/<user>/.cache/dotnet-support`
  when that work directory is yours, and tells you; otherwise it offers to choose a directory. The directory can also be set by hand:
  Settings | .NET → Plugin Data. Nothing changes on Windows or where nothing is restricted.

## 0.1.219

- A lambda whose body returns a value of the wrong type for its delegate is an error, as the compiler reports it: «Cannot implicitly
  convert type 'int' to 'string'» (CS0029, CS0266 when a cast would do) and CS1662 on the returned value — `Label(x => x.Severity)`
  for a `Func<Alert, string>`, also when the parameter's type comes from the call itself.

## 0.1.218

- The C# analysis checks a lambda passed to an overload against that overload's delegate with the parameter types the delegate gives
  (bUnit's `parameters.Add(x => x.Severity, …)`, LINQ over a typed receiver), as the compiler does: far fewer places where it gave up
  (MudBlazor's tests: 15 721 → 9 279 in the debug log).

## 0.1.217

- After the code analysis rested (10 minutes without work), the first edit of a C# file loads its project again, so fresh
  generated files come sooner after the save.

## 0.1.216

- The design-time builds of the projects a project references run side by side, ahead of their turn (at most half of the
  processor cores).

## 0.1.215

- When a solution opens, the generators and analyzers warm up the projects you worked in last time (up to three), so their
  generated types and warnings are there before the first file is opened.

## 0.1.214

- Opening a solution again is faster: the generators and analyzers of C# reuse the design-time builds of its projects from a disk
  cache instead of running MSBuild for each project (MudBlazor: no MSBuild at all on the second open, its 7 projects load in
  8–85 ms each). The cache is used only while every file the build read is unchanged — the imports of the SDK and the packages,
  `Directory.Build.*`, `global.json`, `.editorconfig`, the files of the project, the installed SDKs and the environment.

## 0.1.213

- Which parameter each argument of a call goes to is now decided by the port of the compiler's own rules: a named argument may stand for
  a `params` array, as the compiler allows (`M(x: 1234)` for `M(params int[] x)`). Unused code of the overload resolution removed.

## 0.1.212

- The C# errors and the analyzer warnings are now looked for in the whole solution by default, not only in the open files: Problems →
  Project Errors lists the files nobody opened. Settings | .NET | Language Server → Analysis («Compiler diagnostics for», «Analyzer
  diagnostics for») — these two options apply to the plugin's own analysis too, with the language server off; `openFiles` brings the
  old behaviour back on a large solution.

## 0.1.211

- Inside the analysis of C#: overload resolution is split by concern — the conversions, the choice of the better candidate and the
  applicability each in a file of their own — to be replaced part by part by the port of the compiler's overload resolution. No change
  in what is reported.

## 0.1.210

- The C# analysis infers a generic call from a collection expression argument, as the compiler does: `First([1, 2])` with
  `T First<T>(T[] items)` is `int`, spreads included (`[1, ..more]`); an empty `[]` infers nothing, so `Make([])` is CS0411. Errors
  such calls hid before are now reported.

## 0.1.209

- The C# analysis infers a generic call that has a target-typed `new()` among its arguments from the other arguments, as the compiler
  does (`Pick(new R(), new())` is `R`), instead of giving up on the call.

## 0.1.208

- Inside the analysis of C#: how each kind of argument (a lambda, a method group, a tuple) is read for the type inference lives in a file
  of its own, so a gap the analysis logs for one construct is fixed in one place. No change in what is reported.

## 0.1.207

- The C# analysis no longer gives up on a call whose overloads differ by a non-delegate parameter where a lambda stands
  (`items.ToDictionary(k => k.Id, v => v)` next to the overload with an `IEqualityComparer<TKey>`): a lambda without written parameter
  types tells nothing there, as the compiler sees it.

## 0.1.206

- The C# analysis types a lambda for each overload of its call as the compiler does — with the parameter types that overload gives — so
  calls like bUnit's `parameters.Add(x => x.Severity, Severity.Warning)` are inferred instead of left unknown.

## 0.1.205

- No false "Cannot implicitly convert type 'Task<Task<T>>' to 'Task<T>'" (CS0029) for `Task.Run(() => { return StartAsync(); }, token)`:
  a lambda whose body is a block returning a `Task<T>` picks `Task.Run(Func<Task<T>>)`, as one whose body is an expression already did.

## 0.1.204

- A name inside `nameof(...)` that does not exist is an error, as the compiler says: `nameof(Counter.Missing)` (CS0117),
  `nameof(Undefined)` (CS0103), `nameof(System.Nothing)` (CS0234), `nameof(text.Nope)` (CS1061); `nameof(text.Select)` of an extension
  method is CS8093. What `nameof` allows stays silent: a method group, an instance member through its type, a type parameter.

## 0.1.203

- A project referenced by another one is loaded once by the generators and analyzers, not twice: opening a test file of MudBlazor
  no longer runs the design-time build of its referenced projects again a few seconds later.

## 0.1.202

- Closing a project while its source generators or analyzers are still running no longer reports an internal error of the plugin
  ("Already disposed" in the IDE log).

## 0.1.201

- No false "Possible null reference argument" (CS8604) in the arms of a `switch` expression after a `null => …` arm, nor in the sections of
  a `switch` statement after `case null:` — the value is not null there, as the compiler knows (MudBlazor's `MudSelectContext`, seen in
  the sandbox: with `TreatWarningsAsErrors` the warning looked like an error of the build).

## 0.1.200

- The index of assemblies keeps the explicit implementations of interface members (private in metadata, named by the interface:
  `System.IUtf8SpanFormattable.TryFormat`) — methods, properties, events. The analysis then knows an interface is implemented already:
  by default in another interface (`INumberBase<T>` implements `IUtf8SpanFormattable`, so a `struct T : INumber<T>` owes nothing for
  `TryFormat` — CS0535 was ours alone, the last false error of the Razor corpus) or by a base class of a library. The index format is 6:
  the indexes are made again on the first start, as on an update of the SDK.

## 0.1.199

- Projects built with `UseArtifactsOutput` (the .NET 8 SDK layout of the Aspire templates and eShop: `artifacts/obj/<project>/` and
  `artifacts/bin/<project>/` at the root of the repository, the pivot `debug` / `debug_net8.0`) are restored to the IDE: the assets, the
  built outputs of referenced projects, the C# the build makes in the intermediate folder and the "not restored" banner all find the
  artifacts folder. Before, such a project had no assemblies to the analysis, so a missing name was no error (eShop's Catalog.API, found
  live in the sandbox).

## 0.1.198

- A project with Razor keeps reporting errors while a C# file is typed: an unsaved edit of a `.cs` no longer makes what the Razor generator
  made "stale" (it reads the `.razor` / `.cshtml` files only), so a missing name is red at once, as in any other project (the live check of
  0.1.196 found the project silent until a save). A generator that reads the C# (the JSON one) keeps the old rule until the save runs it.
- The playground: `TYPE:razor-errors-after-generation` uses a line that gives CS0117 and CS0103.

## 0.1.197

- Inside the analysis of C#: the four separate guards against a question asking itself (the type of an expression, a name, a local, the
  type arguments of a call, Roslyn's inference of a call) are one registry with one rule of what is kept; the ported type inference is
  asked once per call per pass instead of up to ten times (the type of the call, the overload checks and the applicability of each
  candidate asked it again). No change in what is reported.

## 0.1.196

- Projects with Razor and Blazor report the errors of their C# like any other project, once the Razor generator has run in the helper
  and what it made is fresh (RAZOR_PLAN.md, R0b done: the corpus of MudBlazor, nopCommerce and eShop is at one false error of the core in
  four solutions). Until the generator has run, or after an edit of a `.razor` file it has not seen yet, a missing name is still no
  error.

## 0.1.195

- The visibility of 0.1.193 reaches everything of a project's sources: an extension method of a project this project does not reference
  is not found (CS1061, as the compiler says), the parts of a type with the same name in several projects are not merged across
  projects, and the checks that stayed quiet because "some project has an extension method of that name" look at the seen projects
  only.

## 0.1.194

- The rules that decided implicit conversions before the ported Roslyn ones are gone (213 lines): the port proves every conversion between
  known types; what is left answers only "not known" (an open type parameter of the method being resolved, a type whose arguments are
  not known) or "none" (both types fully known). The debug log of the analysis showed those rules deciding nothing else. A type
  parameter constrained by `class` or by a class is a reference type to the port: `List<T>` to `IEnumerable<object>` with
  `where T : class` converts.

## 0.1.193

- A type declared in the sources of a project this project does not reference (directly or through another) is not seen, as the compiler
  does not see it: eShop's AppHost references no project, so the `static class Extensions` of the global namespace of four other projects
  is no longer taken for its own `eShop.AppHost.Extensions` (CS0117 "does not contain a definition for 'IsFoundryEnabled'" was ours
  alone). With a reference, the global one wins over the one a `using` brings, as in C#. The Razor corpus is at one false error (MudBlazor,
  a default interface member the index of assemblies does not see yet).

## 0.1.192

- The debug log of the C# analysis says why: an inference that could not finish names the fact it lacked (the type of a lambda body,
  a parameter type, a type parameter fixed to nothing) instead of "the inferrer answered Unknown"; an overload of unknown applicability
  names the argument and the conversion it could not decide.
- The harness over a whole solution (development, `SolutionDiagnosticsHarnessTest`) knows the project of every file as the IDE does, sees
  the files a project links from outside its folder, and runs on one file in seconds (`SOLUTION_HARNESS_FILE`): eShop 13 → 1, the one
  left is a real false error (a `static class Extensions` of the global namespace in nine projects, the one of another project is taken).

## 0.1.191

- The debug log of the C# analysis: every place where the analysis gives up or falls back to a simpler rule — a conversion the ported
  Roslyn rules do not prove, a type inference that cannot finish and why, an overload resolution that cannot pick, a candidate dropped,
  a type asked while being computed — writes one line to the plugin log, category `semantic` (.NET | Plugin Logs, and
  `~/idea-dotnet-logs/plugin/plugin-<day>.log`). Settings | .NET → "Write the debug log of the C# analysis", on by default for now:
  attach the lines of the file and the place to a report of a false error. Nothing is written for what went well.

## 0.1.190

False errors of C# the Razor corpus still showed after 0.1.189. nopCommerce is at none, MudBlazor at one (a default interface member
of `INumber<T>`, the index of assemblies does not see it yet); SberEmp.Shared stays at none.
- The base type of a class declared in another file is resolved by the `using` directives of that file, not of the file that uses it
  (`GetOrderResponse : Order` with `using …Api.Models` there — the `Order` of the shop was taken, and its `int Id` gave CS0029).
- An async lambda given to a delegate returning `ValueTask<T>` returns a `ValueTask<T>`, not a `Task<T>`: no CS0411 on
  `AsyncEnumerable.Select(async (x, _) => …)`.
- `null` given to overloads taking `Func<T, Task>` and `Action<T>` picks the first, as the compiler does: no CS0121.
- A generic overload whose type arguments cannot be inferred from the call is not a candidate (`Should<TCollection, TKey, TValue>` of
  AwesomeAssertions); between `Should<T>(IEnumerable<T>)` and `Should<TKey, TValue>(IEnumerable<KeyValuePair<…>>)` the more specific one
  wins, the receiver of an extension method counted — `dictionary.Should().NotBeEmpty()` is no CS1061 on `ObjectAssertions`.
- An extension method given as a method group with its receiver bound (`_resolve = compilation.GetBestTypeByMetadataName`) converts to a
  delegate taking the remaining parameters.

## 0.1.189

- Implicit conversions are classified as the C# compiler does (Roslyn's conversions, ported): no false "cannot convert" where a type
  parameter goes to its `Nullable` (`T` to `T?` with `where T : struct`), to a type with a user-defined `implicit operator`
  (`T` to `NullableObject<T>`, `double?` to `ChartData<double>`), or from such a type to a type parameter. On MudBlazor the errors in
  `.cs` went from 17 to 7.
- What this classification does not prove, the previous rules decide: a real error stays an error.

## 0.1.188

- The type of a generic call is inferred as the C# compiler does (Roslyn's method type inference, ported): the best type of all the
  arguments, not the first one's — `Pick(1, 2L)` is `long`; lambdas, method groups, tuples and interfaces of the arguments take part.
  What the type of a call decides — completion after it, the type of `var`, the errors of what it is given to — follows.
- Where that inference does not succeed, the previous one answers, as before.

## 0.1.187

False errors of C# found on large Razor and Blazor solutions (MudBlazor, nopCommerce, eShop) — they were in any project, Razor only
showed them. On MudBlazor the errors in `.cs` went from 241 to 17, on nopCommerce from 88 to 5; SberEmp.Shared stays at none.
- A local function given where a delegate is expected (`Inject(props, key, SetHeader)`, `Run(TrackingAction)`) is a method group, not a
  call: no CS1503 "cannot convert from 'void'" or "from 'Task'".
- A verbatim identifier is its name: `@Icons.Material.Filled`, `@class`, `@event` and, in what the Razor generator writes, `@Html`, `@Model`.
- `using static Outer;` brings the nested types of `Outer` too (`MyEnum` of a component, `GuidConverter`).
- After `join x in … into xs` only `xs` is in scope: a later `from x in xs.DefaultIfEmpty()` is no CS1930.
- `int?` converts to `decimal?`, a constant `4` to `byte?`; `span[range]` with a `Range` variable is a slice, not an element.
- The base class of a partial class may be written in another part (`@inherits` of a `.razor`): `base.OnAfterRenderAsync` is found.
- `() => _table?.Expand()` is a valid body for an `Action`; a lambda returning a task picks `Func<Task<T>>` over `Func<T>`
  (`GetAsync(key, async () => …)` of a cache gives the value, not a task).
- A type of a referenced project that is built is the same type as in its sources: no CS1503 "from 'KeyInterceptorService' to
  'KeyInterceptorService'".
- The type inference behind the errors of generic calls is Roslyn's, ported: upper bounds of contravariant interfaces, `T?` with a
  lower bound, interfaces a class implements, type parameters that depend on each other.

## 0.1.186

Razor and Blazor, the first step: the C# of a web project sees what the Razor compiler makes of `.razor` and `.cshtml` files.
- The source generators of a project now include Razor's: the classes of components and pages are in Dependencies → Analyzers →
  Microsoft.CodeAnalysis.Razor.Compiler, byte for byte what the build makes, and completion and Go to Declaration in C# see them
  (`typeof(Counter)`, `nameof(Counter.Step)`).
- Saving a `.razor` or `.cshtml` file runs the generators of its project again (any additional file of the generators does).
- Generators whose assembly name starts with `Microsoft.CodeAnalysis.` but is not Roslyn itself load again (Razor's, the .resx generator).
- Errors in projects with Razor stay off for now, as before: they come once the false errors this showed are fixed.

## 0.1.185

The last false errors of a live solution of libraries (SberEmp.MSR.TSS, a TPM library): none of its 350 files has an error now.
- In an initializer of an enum member a member of any enum is of its underlying type, as the compiler binds it:
  `Bytes = (Bits + 7) / 8`, `Half = Other.Bytes / 2`, `Last = First + Other.Count - 1` are no CS0019.
- A conversion operator of a base class of the solution converts the derived class too (`implicit operator TpmHandle(SessionBase s)` for an
  `AuthSession : SessionBase`): no CS1503 on `ContainsKey(session)`.
- An `int` constant that fits goes with `uint` without widening both to `long`: `uint i = handle & ~(0xff << 24);` is no CS0266.
- The parts of a partial class that each write the same base class: its methods are found once, no CS0121 against themselves.

## 0.1.184

False errors of a live solution of libraries (EFCore.BulkExtensions, Microsoft.Extensions.Options), checked on all its 350 files.
- The members of `object` on an enum, a struct or a delegate of an assembly: `DbType.Binary.ToString()`, `day.GetType()`,
  `action.GetInvocationList()` were CS1061 (the index writes no base of them). `System.Enum` and `System.MulticastDelegate` are classes:
  `Enum e = DayOfWeek.Monday` is no CS0029.
- An enum of the solution with an underlying type (`enum Mode : ushort`) derives from `Enum`, not from `ushort`: `mode.HasFlag(...)` resolves.
- The parameters of a lambda passed to a generic method of a generic receiver inside a generic method
  (`OptionsBuilder<TPolicy>.Configure<IConfiguration>((policy, configuration) => policy.Name = ...)`): `policy` is `TPolicy` again, not
  `IConfiguration`.
- .NET → Collect Full Diagnostics...: everything the plugin knows about the solution in one file — the IDE and `dotnet`, who reports errors,
  what every project is compiled against, every error and warning of every C# file with its line, the analyzers' diagnostics, the
  journal of the plugin. It opens in the editor; the file is in `~/idea-dotnet-logs/diagnostics`.

## 0.1.183

- An assets file whose target is written by the long name of its framework (`.NETStandard,Version=v2.1`, as some restores write it) gets
  its reference pack: before, a library for .NET Standard 2.1 had no `List`, `Dictionary`, `System.Linq`, `IServiceProvider`, and `Type`,
  `Task` came from old contracts without `GetMethod`, `Dispose` (CS0246, CS0234, CS1061 in a live solution).

## 0.1.182

- The reference packs are looked for in the SDK that restored the project (`runtimeIdentifierGraphPath` of `project.assets.json`) before the
  folder of the `dotnet` the IDE found: that one may be a shim (snap, mise, a wrapper script) with no `packs` next to it. A library for
  .NET Standard 2.1 then had no `List`, no `System.Linq`, and `Type` came from an old contract without `GetMethod` (CS0246, CS0234, CS1061).

## 0.1.181

- .NET → Copy Compilation References of Current File: what the plugin compiles the C# file in the editor against — its project, the target
  of `project.assets.json`, the packages and assemblies, which of them are kept, where `System.Type`, `List` and `Enumerable` come from —
  copied as text, to send along when a name the compiler sees is red.

## 0.1.180

- NuGet window: the version list of an installed package shows every version of the feed again. The list was loaded, but the request for
  the package's description made right after it threw the answer away, and the card kept the installed version and the latest only.

## 0.1.179

What a project is compiled against, as the SDK resolves it (false errors in a live solution of libraries).
- One assembly from a framework and from an old package (`System.Runtime`, `System.Threading.Tasks`, `System.Linq` 4.3.0 a dependency
  still brings): the one of the higher assembly version is used, the framework's on a tie, as `ResolvePackageFileConflicts` does. The
  contracts of `netstandard1.x` no longer hide `Type.Assembly`, `Type.IsEnum`, `Task.Dispose`, `Task.WaitAsync`, `Assembly.GetCallingAssembly`,
  `string.Split(string, StringSplitOptions)`, `ToLower(CultureInfo)` (CS1061, CS1503, CS1501).
- A library for .NET Standard 2.0 is compiled against the reference assemblies of `NETStandard.Library` (`build/netstandard2.0/ref`):
  `List`, `Dictionary`, `IServiceProvider`, `System.Linq`, `System.Collections.Concurrent` resolve there (CS0246, CS0234). .NET Standard 2.1
  and .NET Core 3.x find their reference packs too.
- `IList<T>` converts to `IEnumerable<object>` when `T` is a reference type by its constraints (`where T : class`, a base class): no CS1503.

## 0.1.178

The header of a type, as in Rider.
- The base list (`class X : |`) and a constraint (`where T : |`) offer the types of the assemblies too (`ControllerBase`, `Exception`,
  `IDisposable`), imported or with their namespace, by the same filter as the solution's types: a base class first and only then
  interfaces, nothing sealed, static or a struct; a struct, an interface and a record struct take interfaces; no own type parameter.
- `where` past a base list or a constraint of a generic declaration; after `where` the type parameters not constrained yet.
- Live templates have Rider's scopes: statement, expression, type member, type declaration, namespace. `class X : wh` offers no
  `while` template any more, a member place no `if`, a body no `prop`; `#region`, `#if` and `nguid` go everywhere as before.

## 0.1.177

The errors of a broken Jellyfin compared with `dotnet build` (`docs/compare/jellyfin-broken-2026-10-13.md`): 35 of Roslyn's 45 before,
all 45 now on the same code at the same place.
- No more errors after an error: a name declared twice in one scope (CS0128) binds to the first declaration, as Roslyn, so its uses get
  no CS0029 / CS1503 / CS0168 of the second one; a `break` / `continue` with no loop around it (CS0139) ends no path, so no CS0162
  after it; a lambda of several `return`s has their best common type (`return series;` / `return i;` give `BaseItem`), not the first.
- CS0029 where no conversion goes, found by the conversions of overload resolution (base types, interfaces, nullable, user-defined
  operators by their operand): `string` → `DateTime?` / `Guid` / a class, `DateOnly` → `DateTime?`, `IEnumerable<T>` → `int`,
  `int` → `ActionResult<T>`; also an assignment through an indexer (`map[key] = value`). Not where an explicit conversion may exist
  (that is CS0266).
- CS7036 / CS1503 of a method or constructor whose parameter types the calling file does not import (`IReadOnlyList<Regex>`): the
  types are now read with the usings of the method's own file. CS1729 / CS7036 for a target-typed `new(…)`. CS0311 when an extension
  method of the same name but another number of type parameters is around (`GetItemById<string>`).
- Corpus case `jellyfin-broken` in `debug-playground/Broken/RiderComparison.cs`, tests `CSharpSemanticErrorsTest`,
  `CSharpSemanticWarningsTest`; the harness `JellyfinDiagnosticsHarnessTest` runs the checks on real Jellyfin sources with the real
  assembly indexes (only with `JELLYFIN_HARNESS` and `LATENCY_HARNESS` set).

## 0.1.176

- Errors the editor did not show, only the build (the user's `WeatherForecastController`): CS0029 for a condition of `if` / `while` /
  `do` / `for` / `?:` that is not a `bool` (an enum, a struct, a class without `operator true`); CS0029 in an object initializer
  (`new Forecast { Date = DateTime.Now }`, its members were not checked at all); CS0029 between structs that declare no conversion
  operator (`DateTime` → `DateOnly`); CS0029 for an interpolated string given to a number, `char` or `bool` (`TemperatureC = $"123"`).
- Warning CS8618 on a field or auto-property of a type with no constructor written (`public static readonly ILogger<T> _logger;`):
  before, only written constructors were checked.
- Corpus case `weather-controller` in `debug-playground/Broken/RiderComparison.cs` (all five errors of Roslyn matched, nothing extra),
  nullable cases in `nullableFlow/Cases.cs`.

## 0.1.175

- The `switch` live template writes an empty body, as Rider's (`docs/compare/jellyfin-2026-10-12.md`, № 12): `switch (|)` with the
  list of names, then the caret in `{ }` — no more `case :` / `break;` / `default:` skeleton to fill or delete. The sections come
  from the gray `case OperationType.Insert:` that now shows in the body as soon as the template ends (Tab one by one), or all at once
  from Alt+Enter "Add 'switch' sections". Tests `CSharpLiveTemplatesTest`, `CSharpCompletionInsertionTest`, scenario
  `TYPE:switch-template` in `Console/Editor/SwitchCompletion.cs`.

## 0.1.174

Stage 7 of the fixes found comparing with Rider on Jellyfin (`docs/compare/jellyfin-2026-10-12.md`, section 9): the large refactorings.
- Change Signature (Alt+Enter "Change signature…", Refactor This, Ctrl+F6): a dialog with the name, the return type and the parameters
  (modifier, type, name, default value, value at call sites), a preview; the declaration and its whole hierarchy and every call across the
  solution updated (arguments reordered, named ones kept by name, removed ones dropped, added ones with the value at call sites, `new`,
  `this(…)`, `base(…)`, local functions); conflicts (method groups, a removed parameter used in the body…) shown before the change, one undo.
- Safe Delete (Alt+Delete, Refactor This): types (all partial parts, the file when it holds only the type), members with their doc
  comments, enum members, field declarators, parameters (in the whole hierarchy, with the arguments of every call and the `<param>` line),
  locals (a value with side effects kept), local functions, type parameters (with `where`, `<typeparam>` and the type arguments of the
  uses); remaining usages shown as conflicts, as in Rider.
- Extract Interface / Extract Superclass / Extract Class (Refactor This, a dialog of members): signatures with doc comments and type
  parameters; moved or abstract members, private helpers made protected, the old base passed on; a field of the new class for Extract
  Class (the simple case).
- Pull Members Up / Push Members Down (direct bases and derived types: moved members, abstract declarations, accessibility, `using`s,
  conflicts) and Use Base Type Where Possible (parameters, locals and fields used only through a base's members).
- Tests `CSharpChangeSignatureTest`, `CSharpSafeDeleteTest`, `CSharpExtractTypeTest`, `CSharpHierarchyRefactoringsTest`.

## 0.1.173

Stage 6 of the fixes found comparing with Rider on Jellyfin (`docs/compare/jellyfin-2026-10-12.md`, section 9): the actions of medium
size, named as Rider names them.
- Introduce Variable replaces all occurrences across the member (the variable in the innermost block holding them; not when something
  the expression reads changes between them). Move type to file leaves both files clean (no unused `using`s, the namespace style of
  `.editorconfig`, the doc comment with the type, no blank line left behind). Refactor This: "Move to Another File", "Move Type to
  Another Namespace", "Move to Folder", "Extract Members to Partial", "Inline Method", "Copy Type".
- Members: "To property with backing field", "To property with 'field' keyword" (C# 14), "To computed property", "To auto-property",
  "Encapsulate field", "To factory method" (calls in the file), "To explicit / implicit implementation".
- Switch and parameters: on a switch expression "To 'if' statements" and "To ?: expression", "Add condition component", "Generate type
  patterns" (the direct subtypes of the solution), "Create overload without parameter", "Chop parameters list" / "Put parameters on one
  line"; C# 14 "Convert to extension block".
- Types and docs: "Add exception documentation", "Import static members", "Inline Method" (a call, or all calls and the method),
  "Create derived type", "Copy type", "Cover" on a test.
- Tests `CSharpContextActionsTest`, `EditorExtrasTest`, `CSharpMoveTypeTest`, `CSharpMemberActionsTest`, `CSharpSwitchActionsTest`,
  `CSharpParameterActionsTest`, `CSharpExtensionBlockActionsTest`, `CSharpTypeActionsTest`.

## 0.1.172

Stage 5 of the fixes found comparing with Rider on Jellyfin (`docs/compare/jellyfin-2026-10-12.md`, section 9): the list, its order
and its speed.
- After `new ` the solution's types the variable's name or the expected type suggest go above the assemblies' (`UserPolicy` before
  `LockRecursionPolicy`); after a dot on a string, a list or an array `[]`, `Count`, `Length` come first, as in Rider, and `[]` writes
  `text[|]`.
- Faster completion: the stub index of type names is read once per name and request (it was read two or three times), the conversions
  of SMART completion look types up in a per-set cache, the visible namespaces are computed once per place. On a harness with the
  real assembly indexes of Jellyfin and ~4300 solution types: `new ` 230 → 130 ms, SMART `new ` 200 → 85 ms, an argument 160 → 90 ms.
  The cap of 2000 type names now counts the names of the file's project only (before, other projects' keys could push the project's
  own types out of the list).
- Tests `CSharpCompletionOrderTest`, `CompletionLatencyHarnessTest` (run with `LATENCY_HARNESS` set).

## 0.1.171

Stage 4 of the fixes found comparing with Rider on Jellyfin (`docs/compare/jellyfin-2026-10-12.md`, section 9): new context actions
as Rider names and writes them.
- Modifiers: "To private" / "To internal" / "To public" with the other legal accessibilities in a submenu (none on overrides, explicit
  implementations, interface members), "To virtual", "To abstract" (in an abstract class: the body goes), "Make partial".
- `if` and null: "Invert 'if'" in the middle of a body (the rest of the block becomes the new branch when the old one exits), "Invert
  condition" on `?:`, "Check variable for null", "Check expression for null", "Assert expression is not null", "Split variable
  declaration and initialization".
- Expressions: "To safe cast", "To regular / verbatim / raw string" (only where exact), "To static method invocation" and back,
  "Generate try-catch block", "Convert initializer to assignment".
- Tests `CSharpModifierActionsTest`, `CSharpIfActionsTest`, `CSharpParameterActionsTest`, `CSharpContextActionsTest`,
  `CSharpExpressionActionsTest`; scenarios `Console/Editor/ModifierActions.cs`, `ExpressionActions.cs`.

## 0.1.170

Stage 3 of the fixes found comparing with Rider on Jellyfin (`docs/compare/jellyfin-2026-10-12.md`, section 9): Refactor This and
Alt+Enter where Rider has them.
- Refactor This lists Introduce Field / Introduce Parameter only where they apply (an expression in a body), not on every header.
- Introduce Variable where Rider offers it: an `await` without a result (`var publish = Publish(); await publish;`), the caret on a
  receiver (`user.ProfileImage`), on the type of a cast (the whole cast), a switch expression of collections (the type of the target),
  the expression body of a lambda (the lambda gets a block).
- "Sort 'using' directives" on any block of two or more directives, honouring `dotnet_sort_system_directives_first`; "Add argument
  name 'pattern'" with the caret in a string literal of an attribute argument; new navigation rows in Alt+Enter on a member header:
  "Incoming Calls", "Outgoing Calls", "Implements … / Overrides …".
- Tests `CSharpRefactorThisTest`, `CSharpContextActionsTest`, `CSharpUsingsTest`, `CSharpParameterActionsTest`,
  `CSharpNavigationIntentionsTest`; scenario `Console/Editor/RefactorThis.cs`.

## 0.1.169

Stage 2 of the fixes found comparing with Rider on Jellyfin (`docs/compare/jellyfin-2026-10-12.md`, section 9): typing and the list.
- Gray text: after postfix `.null` / `.notnull` the guard body (`throw new ArgumentNullException(nameof(x));`) comes by itself; the
  guard throws for a parameter behind calls that check arguments (`ThrowIf…`, `ArgumentNullException.ThrowIfNull`); `foreach (var
  preference in |` takes the one collection whose elements are of the type the element is named after.
- Postfix `.foreach`: the element named by its type when the collection's name gives none (`char` → `c`), the type a stop of its own
  (`var` / the element type). A member template inserted under a member (`ctor`, `prop`…) gets an empty line above it.
- The order of the list as Rider has it: `new` no longer lifted second in a condition or an argument (`newName`), the statics of
  imported types below the keywords (`Thro` → `throw` second), in an argument the values of the parameter's type first, then `default`,
  `nameof`, `null` where they fit, a type (`String`) no longer lifted by its name.
- Tests `GuardGhostTest`, `AnalogyGhostTest`, `PostfixAndSurroundTest`, `CSharpLiveTemplatesTest`, `CSharpParameterActionsTest`,
  `CSharpCompletionOrderTest`.

## 0.1.168

Stage 1 of the fixes found comparing with Rider on Jellyfin (`docs/compare/jellyfin-2026-10-12.md`, section 9): what broke code or
showed everywhere.
- Typing: a space after a keyword typed in full (`foreach (var `) no longer commits a row matched by humps (`ValidUsernameRegex`);
  at a statement start the statement keywords go above the value keywords, so `tr` + Tab is the `try` block, not `true`.
- "Add missing arms" is not offered when the switch expression has an unconditional `_` arm (it made the members that went to `_`
  throw). "Inline variable" is not offered for a value with side effects (`new`, a call, `await`…) used more than once (it made two
  objects). "Wrap in 'using'" only for `IDisposable` / `IAsyncDisposable`. "Add a partial part" and "Create test" only on the header of
  a type (and of a member for the test), not anywhere in it.
- Postfix: `.if` after a number (`x.Length > 0.if`) takes the whole condition; `.return` is not offered in a function returning nothing.
- New context actions as in Rider: "Initialize field from constructor" on a field no constructor assigns (a parameter and the
  assignment in each constructor, a new constructor when there is none, the primary constructor's parameter), "Initialize members from
  constructor" for all of them. Scenario `Console/Editor/FieldActions.cs`.
- Stability: the semantic colors no longer depend on which other files happen to be parsed in memory (the same file got 6308 or 6316
  colored names); the conflicts of a rename across the solution are computed in the background, not on the EDT.
- Tests `CSharpCompletionInsertionTest`, `CSharpSwitchActionsTest`, `CSharpContextActionsTest`, `CSharpUsingsTest`, `EditorExtrasTest`,
  `PostfixAndSurroundTest`, `PostfixByTypeTest`, `CSharpFieldActionsTest`, `CSharpSemanticColorsIdempotenceTest`, `CSharpSolutionRenameTest`.

## 0.1.167

- Grey text that ends on the name of a method gets its call, as the row of the list writes it: `.Sel` + the network's `ect` + Tab is
  `.Select(|);` with the parameter info, `Save` + Tab is `Save();` — before, `.Select` without parentheses. Not where a `(` or `<`
  follows, for a property, after `+=` / `-=` (a method group) or in `nameof(`. Test `GhostCallsTest`.

## 0.1.166

- The context actions of 0.1.162–0.1.163 (`switch`, `if`, loops, parameters) have their descriptions with before / after examples:
  without them Settings | Editor | Intentions and the search of the settings failed with "Intention Description Dir URL is null".
  Test `IntentionDescriptionsTest` checks every intention of the plugin.
- Completion: the keywords are back in two places where the list had only types. `publ` on a line of its own over the attributes of a
  type (`[ApiController]`) — the parser reads `publ [ApiController]` as an array type of an incomplete member; `ca` in a `switch` that
  has no section yet — the parser takes the word out of the switch, now `case` / `default` are offered after its `{`. Test
  `CSharpKeywordRowsTest`.
- No false errors on code that builds, found on Jellyfin: CS1503 where a user-defined implicit conversion of an assembly applies
  (`string` → `ReadOnlySpan<char>`, `byte[]` → `ReadOnlyMemory<byte>`, `string` → `PathString`) and for a tuple literal argument
  converted element by element; CS0535 on a class of a generic interface that implements its base interface's members by default;
  CS0117 / CS1061 on `internal` members of a project whose friend is declared as an `<AssemblyAttribute Include="…InternalsVisibleTo">`
  item; CS0246 on types of a shared framework (`Microsoft.AspNetCore.App`) a referenced project passes on. Corpus case
  `jellyfin-conversions`, tests `CSharpOverloadErrorsTest`, `CSharpSemanticErrorsTest`, `CSharpAccessErrorsTest`, `ProjectReferencesTest`.

## 0.1.165

- Gray text: `break;` under a `case` / `default` label is offered only in an empty section — not when the section already ends with
  `break;` or has a statement below the caret. The `switch` live template writes `break;` under the caret, and the first suggestion
  after it doubled the line. Test `GhostTextTest`.
- Gray text in a `switch` with the completion list open: the label follows the selected row — `case |` with `Update` selected shows
  `OperationType.Update:`, the keyword `case` selected on a line of the block shows the first label — and Tab takes it. A row chosen
  with Tab or Enter (the keyword `case`, a member of the enum) asks for the gray text by itself: before, nothing appeared until the
  next key (Space, Enter, Backspace). Test `TypingGhostTest`, scenario `TYPE:switch-typed-case` in `Console/Editor/SwitchCompletion.cs`.

## 0.1.164

- CS0119 ("'X' is a type, which is not valid in the given context") is reported by the editor in every position where a value is wanted,
  not only in an argument, an initializer, the right of `=` and a `return`: the header of `switch` / `if` / `while` / `do` / `foreach` /
  `lock` / `using`, a `throw`, an interpolation, an `await`, the parts of `?:`, the operands of operators other than the type side of
  `is` / `as`. `switch (OperandType)` showed the error only "From the last build" before. Corpus case `type-as-value-positions` in
  `Broken/RiderComparison.cs` (8 CS0119 of Roslyn, all matched), test `CSharpOperatorErrorsTest`.
- ML build: `-Pml.modelSize=50` (the default) or `100` picks the network the zip carries, `cs50m-caret-ft5e5.cml` or
  `cs102m-caret-ft5e5.cml` (the 100 M one: rest of line exact 46.1 % against 43.8 %, about twice the latency and memory); the zip of
  the 100 M build is `…-ml-100m.zip`. The plugin loads whichever of the two is bundled, and either by its own name from the models
  directory of the settings (`CSharpMlModels.NN_MODELS`).

## 0.1.163

- Context actions on `if` (`lang/NativeCSharpIfActions.kt`), as Rider's: "Invert 'if'" (the condition negated as Rider writes it, `==` ↔
  `!=`, `<` ↔ `>=`, `is null` ↔ `is not null`, De Morgan over `&&` / `||`, the branches swapped; without `else` at the end of a body the
  guard form `if (!c) return;` / `continue;`), "Merge nested 'if' statements", "Split into two 'if's", "Convert to guard clause", "Add
  braces" / "Remove braces" (also on `else`, `while`, `for`, `foreach`, `using`, `lock`; not when an `else` would dangle). Test
  `CSharpIfActionsTest`, scenario `Console/Editor/IfActions.cs` (`TYPE:if-*`).
- Context actions on loops and parameters (`NativeCSharpLoopActions.kt`, `NativeCSharpParameterActions.kt`): "Convert 'foreach' to 'for'"
  (arrays, strings, lists), "Convert 'for' to 'foreach'" (when the index only reads `items[i]`), "Reverse 'for'", "Iterate via 'foreach'"
  on a collection statement, "Check parameter for null" (`ArgumentNullException.ThrowIfNull(name);` where the assemblies have it, else
  the `if … throw` form), "Add argument names" / "Remove argument names" by the resolved overload. Tests `CSharpLoopActionsTest`,
  `CSharpParameterActionsTest`; scenarios `Console/Editor/LoopActions.cs` (`TYPE:loop-*`), `ParameterActions.cs` (`TYPE:param-*`).
- Gray text, guard rules (`NativeCSharpGuardGhost.kt`): on the first empty line of `if (x == null)` / `is null` / `IsNullOrEmpty(x)` /
  `!cond` blocks `throw new ArgumentNullException(nameof(x));` for a parameter guarded first in the body, else `return;` / `return
  false;` / `return null;` by the result or `continue;` in a loop; in an empty `catch` `throw;`, or `_logger.LogError(e, "Method
  failed");` when the type has an `ILogger`; in a parameter list `Oper` → `ationType operationType` when exactly one visible type begins
  so. Test `GuardGhostTest`, scenario `Console/Editor/GuardGhost.cs` (`TYPE:guard-*`).
- Gray text, rules by analogy (`NativeCSharpAnalogyGhost.kt`): the statement of a `switch` section repeated from the previous sections
  with this label's member substituted (`return "Update";` after `return "Insert";`, also calls and switch-expression arms; needs two
  alike sections), `foreach (var item in |` → the one enumerable in scope or the one named as the plural, `for (var i = 0; i < |` →
  `items.Count; i++` / `.Length`, `nameof(|` → the one parameter or the one the enclosing `if` tests, `string.IsNullOrEmpty(|` → the one
  `string` in scope. Test `AnalogyGhostTest`, scenario `Console/Editor/AnalogyGhost.cs` (`TYPE:analogy-*`).
- The rules of the gray text now live in their own files (`NativeCSharpGhostRule`, listed in `NativeCSharpTypingGhost.rules`); every rule
  is counted in the suggestion statistics under its own name.

## 0.1.162

- Live templates: `switch`, `if`, `while`, `do`, `foreach`, `lock` and `using` open the completion list at their first variable instead of
  writing a placeholder (`switch (|)` with the names in scope, then `case |:` with the members of the enum), as Rider's `completeSmart()`
  does. Test `CSharpLiveTemplatesTest`, scenario `Console/Editor/SwitchCompletion.cs` (`TYPE:switch-template`).
- Gray text: the next label of a `switch` over an enum, by a rule (`NativeCSharpTypingGhost`, rule "next case"): on a line of the block
  at the labels' indent `case OperationType.Update:` for the first member no section covers (qualified as the sections qualify theirs),
  `default:` once every member is there; after `case`, `case Op` or `def` typed, the rest of it; in a switch expression
  `OperationType.Update => ` and `_ => `. Nothing inside a section's statements, in a `switch` over anything else, or when nothing is
  missing. Rider shows the same line from its local network (Full Line Code Completion); here no model is needed. Test
  `TypingGhostTest`, scenarios `TYPE:switch-next-case`, `TYPE:switch-typed-case`, `TYPE:switch-default`, `TYPE:switch-no-case`,
  `TYPE:switch-expression-arm` in `SwitchCompletion.cs`.
- Context actions on `switch` (Alt+Enter on the keyword, `lang/NativeCSharpSwitchActions.kt`), as Rider's: "Add 'switch' sections"
  (the sections of the members of the enum no label covers, and `default`), "Add missing arms" (the same for a switch expression,
  `=> throw new NotImplementedException()`), "Add 'default' section", "Add section braces", "To 'switch' expression" (sections that
  return, throw or assign one variable), "To 'if' statements" (`==` for constants, `is` for patterns, `||` for several labels; not with
  `goto`), "To 'switch' statement" (a switch expression returned or assigned). The server's rows of the same names stand back with
  Built-in. Test `CSharpSwitchActionsTest`, scenario `Console/Editor/SwitchActions.cs` (`TYPE:switch-add-sections` … `TYPE:switch-to-statement`).

## 0.1.161

- Completion: a statement keyword chosen from the list expands the live template of its name, the same as the key typed in full and Tab:
  `tr` + Tab writes the whole `try { } catch (Exception e) { throw; }` block, `sw` + Tab the `switch` statement with its sections, likewise
  `if`, `else`, `for`, `foreach`, `while`, `do`, `using`, `lock`, `checked`, `unchecked`, `unsafe`. Before, the list hid the template behind
  the keyword and the keyword wrote just itself, so `tr` + Tab gave `try` and `try` + Tab the block. The row says what it writes (`try  try
  catch`); a template the user switched off is not expanded; a keyword chosen by typing a character (`if` + `(`) stays as it was.
  Test `CSharpCompletionInsertionTest`, scenario `Console/Editor/CompletionInsertion.cs` (`TYPE:insertion-keyword-template`).

## 0.1.160

- ML grey text, healing v2 (engine `ml-core`, `NnCompletion.Options.healTrailingSpace`): the spaces and tabs right before the caret are
  part of what the user typed, the prompt of the network ends at the last non-blank byte of the line (`return ⟨⟩` → the prompt ends at
  `return`, the first token has to start with the space; `x =  ⟨⟩`, `Foo(\t⟨⟩`, `Foo( ⟨⟩)` alike; the indentation of an empty line is
  left alone). Before, the prompt ended with a lone space token and the network had to write the word without its space — a split it
  never saw in training; fine-tuned on such prompts, `cs50m-caret-ft5e5` learned it and dropped the space where nobody typed one
  (`Task.FromResult(⟨⟩)` → `newHelloReply {`). The inserted text never doubles the space. The glue at `Task.FromResult(⟨⟩)` itself
  (no whitespace before the caret) goes away only with the network retrained on data built with the same rule — that retraining is
  under way; until then the plugin adds no work-around. The prefill of the KV cache at file open goes through the same rule, so the
  first keystroke after `return ⟨⟩` reuses the whole cache. Test `CSharpNnModelTest.theTypedSpaceAfterReturnIsTyped`.

## 0.1.159

- ML grey text: a new network, `cs50m-caret-ft5e5.cml` (`CSharpMlModels.NN_MODEL`), is the one network of the ML build: the 31 M default
  (`cs31m-e2-lr2e3.cml`), the big model `cs50m-e3-lr2e3.cml`, `-Pml.big=true` and the setting "Use the big model" are gone. The new network
  has the 50 M architecture and vocabulary of the old big one (about 1.6× the latency of the 31 M network), trained from scratch on a
  larger corpus (6.47 G tokens, 27 948 repositories) and fine-tuned on 1.05 M caret positions in the plugin's prompt format, loss on the
  rest of the line and the stop token only (csharp-dataset-prepare `docs/experiments/README-RU.md`). On 12 000 positions of repositories
  no model has seen: rest of the line exact 43.8 % (old 50 M: 32.7 %), shown at the gate 28.9 % of positions (15.4 %), 89.6 % of the
  shown lines exact (81.3 %). A fluent chain is now often completed on one line with its brackets closed instead of going on below an
  open bracket (`CSharpNnModelTest.aFluentChainIsContinuedWithItsBracketsClosed` accepts both). The old files stay in `ml-models/csharp`.

## 0.1.158

- A C# file of a project that the loaded solution does not have (another solution of the folder, e.g. `ShopApi/` next to the root
  solution) is served by the plugin as with the language server off: completion, types, errors, navigation and highlighting no
  longer wait for a server that does not know the file. Coverage by the server is decided by the file's project, not by the folder.

## 0.1.157

- ML grey text: the network is no longer silenced where another grey-text provider of the plugin is enabled but has nothing to
  suggest (for example, a type name selected in the completion list). Turning the ML options on or off applies at once, no restart.
- The `ml-return-line` scenario of the playground now says to press Enter: the grey text answers a change of the document, not a
  caret move.

## 0.1.156

- A C# file of a project that the loaded solution does not contain, but another solution of the folder does, gets a banner: "This
  file belongs to ShopApi.sln, which is not loaded: library members, completion and errors are limited." Load ShopApi.sln switches
  the language server to that solution; Don't Show Again hides the banner for the project.

## 0.1.155

- Debugger: tuple elements show their declared names, as in Rider. A local or a parameter declared as `(Id: 1, Name: "tuple")` is
  shown as `(Id: 1, Name: "tuple")` with children `Id` and `Name` instead of `Item1` / `Item2`, nested tuples too, in Variables and in
  Evaluate of the variable's name. Fields, tuples of eight or more elements and nullable tuples keep the adapter's names.

## 0.1.154

- An explicit interface implementation (`IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();`) is no longer a candidate of a
  call by its simple name: no false CS0121 "The call is ambiguous between 'GetEnumerator()' and 'GetEnumerator()'", as with Roslyn.
- Completion after a dot no longer offers explicit implementations (`void IDisposable.Dispose()`) or other members of a class's
  interfaces; Go to Declaration and parameter info skip explicit implementations too.
- The C# stubs remember explicit implementations, so a file that is not open answers without being parsed (stub version 3: the
  index is rebuilt once).
- Compiler-messages corpus: 310 of 404 errors matched, no false positive (the explicit-implementation probe was added).

## 0.1.153

- Find Usages lists every occurrence of a C# symbol on its own row, as in Rider: two usages on one line
  (`Counter + 1 + Counter.ToString()`) are no longer merged into one result by the Usages view, and the count includes all of them.

## 0.1.152

- No inlay type hint after `var` when the initializer already says the type: `new T(...)`, a literal, `default(T)`, `typeof(T)`,
  a cast or `as`, an enum member, `nameof(...)`; calls, LINQ, `await`, member accesses and `new()` keep their hints.
- Settings | .NET | Behavior: "Hide type hints for obvious initializers" (on by default) turns this off.

## 0.1.151

- The context menu of a project in the Solution view follows Rider: Add, Build, Rebuild, Clean, Run, Debug, Manage NuGet Packages,
  Edit Project File, Properties, Reload, Rename Project, Move to Solution Folder and Remove from Solution stay on top; Publish,
  Run MSBuild Target, Analyze Upgrade, Calculate Code Metrics, Code Analysis, Format and Verify Formatting moved to a Tools submenu.
- The disabled Cut / Copy / Paste and the duplicate Rename... are gone from solution, solution folder and project nodes (files and
  ordinary folders keep them).

## 0.1.150

- The run configuration selected after generation is the startup project: the first executable project of the solution that is not
  an Aspire AppHost, instead of the first by name (`AspireHost: https` in the playground); a choice you made yourself is kept.
- The welcome page opens only on the first installation and on a new minor version (0.1.x to 0.2.x); other updates show a
  "C# Project Support updated to ..." notification with a What's New action.
- Unit Tests explorer: the project node says "N test methods" and, after a run, what the run reported (passed, failed and skipped,
  theory cases included) instead of a count that misses theory cases and skipped tests.

## 0.1.149

- Variable name suggestions no longer include a keyword in its `@` form (`@string` for a `StringBuilder`) when other names are
  available.
- The plugin journal lists the first compiler errors of a failed build next to the "failed with N errors" line (the build before a
  launch too).
- The assembly indexer is not started again when the assemblies, the .NET SDK and the framework are unchanged since the last run
  (a live session showed 93 runs of the indexer process for nothing).

## 0.1.148

- The completion list after a dot shows generic methods as such, one row per name and arity as in Rider: `AddSingleton(Type serviceType,
  Type implementationType) (+ 7)`, `AddSingleton<TService>() (+ 5)`, `AddSingleton<TService, TImplementation>() (+ 3)` instead of one row
  of the first overload with everything else behind `(+ 15)`. A generic row whose parameters do not tell the type arguments inserts
  `AddSingleton<|>()` with the caret between the angle brackets; `Select`, `Where`, `OfType`-like ones with inferable arguments insert
  the plain call.
- Live check: `debug-playground/ShopApi/Playground/GenericMethodRows.cs` (`TYPE:generic-rows`, `TYPE:generic-inferred`).

## 0.1.147

- The parameter of a lambda next to an overload taking `Delegate` is typed, as in Rider: minimal APIs (`app.MapGet("/", context => …)`,
  `MapPost`, `Map`, `MapMethods`) see `context` as `HttpContext` in the hover, in the completion list after `context.` and while the body
  is still being typed; Go to Declaration opens the `RequestDelegate` overload, the inlay hint says `requestDelegate:`. A lambda with
  untyped parameters has no natural type (C# 10), so `Delegate` / `object` / `Expression` never take it: CS8917 where one is expected
  (`Delegate d = x => x;`), and a block body without `return` is CS1643 of the `RequestDelegate` overload, as Roslyn reports it.
- The parameter of a lambda given to a constructor is typed too (`new Timer(state => …)`, `new Thread(argument => …)`).
- Live check: `debug-playground/ShopApi/Playground/LambdaParameterTypes.cs` (`TYPE:lambda-*`) — minimal APIs, middleware, endpoint
  filters, DI, options, logging filters, `Timer`, `Thread`, events, LINQ, `Parallel`, `Sort`.
- Compiler-messages corpus: 309 of 403 errors matched, no false positive (0.1.146: 306 of 400; the lambda probe was added).

## 0.1.146

- The rules of `ref`, as Roslyn reports them: a `ref` field outside a `ref struct` (CS9059), a property returning by reference without
  `get` or with `set` / `init` (CS8146, CS8147), an override or an interface implementation whose return differs by reference (CS8148,
  CS8152), `return ref` in a function returning by value and the reverse (CS8149, CS8150), `return ref` of a wrong type, a non-variable,
  a range variable, a plain local or parameter or a member of one, a `ref` local initialized from one, an instance member of a struct
  (unless `[UnscopedRef]`), a `readonly` field or a member of one by writable reference (CS8151, CS8156, CS8159, CS8168, CS8166, CS8169,
  CS8167, CS8157, CS8158, CS8170, CS8160, CS8161, CS8162, CS8333); `ref` locals without an initializer, with a value, or a value local
  with `= ref` (CS8174, CS8172, CS8171), `= ref` of a non-variable or another type (CS1510, CS8173); a `ref struct` result returned
  with `ref local` for a parameter that is not `scoped` (CS8347 with CS8168).
- Library members too: the index says a `ref` return by the type of the member and, from format 5 of the index, `ref readonly` by a
  flag (`ReadOnlySpan<T>.this[]`, `GetPinnableReference`), so `return ref span[0]` by writable reference is CS8333 and
  `ref int r = ref span[0]` CS8329, as for an `in` parameter; a member of one is CS8334. The index cache is rebuilt once (format 5).
- A private nested type is seen by the private members of its owner: no false CS0050–CS0052 on them.
- Compiler-messages corpus: 306 of 400 errors matched, no false positive (0.1.144: 253 of 376; two ref probes were added).

## 0.1.145

- ML completion is off by default in a build that bundles the models: both the ranker of the completion list and the grey text are turned
  on by the user in Settings | .NET | ML completion; until then the models are not even loaded.

## 0.1.144

- Definite assignment of structs, field by field, as Roslyn: `s.f = 1` assigns the field, reading `s.f` before that is CS0170 («Use of
  possibly unassigned field»), reading `s` needs every field (CS0165), an `out` struct parameter needs every field assigned (CS0177);
  auto-properties count as fields only a whole assignment sets. A local read inside its own initializer (`Del d = delegate { d(); }`,
  `int x = x + 1`) is CS0165; the first source of a query expression is read where it stands.
- CS0161 («not all code paths return a value») on local functions, and on an `async` method whose return type is nowhere (Roslyn goes
  on with an error type); an `out` parameter of such a type still needs assigning (CS0177).
- Compiler-messages corpus: 253 of 376 errors matched, no false positive (0.1.143: 231 of 363; a definite-assignment probe was added).

## 0.1.143

- Compiler errors of declarations, as Roslyn reports them: inconsistent accessibility (CS0050–CS0061, CS7025: a return, parameter, field,
  property, event, indexer, operator, delegate or base type less visible than what names it), modifiers not valid for the item (CS0106:
  `virtual` on a field, `static` on an indexer, `readonly` on a member of a class, `public` on a local function; CS1527 / CS1530 on a type of
  a namespace), `static const` (CS0504), a cycle in a struct layout (CS0523), a partial method without its other half (CS0759 / CS8795,
  when every part of the type is in the file and no generator may add one).
- `await` of a value whose type has no `GetAwaiter` (`await id` on an `int`): CS1061 on the value, as Rider shows it.
- Compiler-messages corpus: 231 of 363 errors matched, no false positive (0.1.142: 203).

## 0.1.142

- A `using` of a namespace that is nowhere is an error on the directive (CS0246 / CS0234), and the rest of the file is checked all the
  same, as the compiler does; before, one unresolved `using` silenced every «does not exist» error of the file.
- An accessor without a body (`int Value { get }`) reports CS8180 as Roslyn does, not `; expected`.
- A corpus of the compiler-messages pages of dotnet/docs (250 examples) is checked against Roslyn: `CompilerMessagesCoverageTest` and
  `src/test/resources/compilerMessages/coverage.txt` show which compiler errors the plugin reports and which it still lacks.

## 0.1.141

- A syntax error silences the semantic checks of its own statement only, not of the whole member: with an unterminated string in one line,
  `this.db` two lines below (a primary-constructor parameter, no such member) gets its CS1061, as in Rider. An error in a signature or an
  expression body still silences the member, top-level code the file.
- A name that is nowhere (CS0103, CS0246, CS0234, CS1061, CS0117) is painted as an unresolved reference (red text of the colour scheme) on
  top of the squiggle, as Rider does.

## 0.1.140

- ML grey text goes on to the next lines while a bracket of the caret's line is open: `.WithMetrics(metrics => metrics` (the file writes its
  chains line by line) comes with `.AddMeter(…)`, `.AddAspNetCoreInstrumentation()`, `.AddPrometheusExporter());` below it, Tab inserts the block.
  Every line is asked on its own and must pass the empty-line threshold; the block ends where a line fails it, where the brackets close, or at
  8 lines. A line with its brackets balanced is never continued, so one-line suggestions are as before. Settings | .NET | ML completion →
  "Continue to the next lines while a bracket is open" (on).

## 0.1.139

- ML grey text: a certain start of an uncertain line may end with `;` (`return NotFound();` was shown as `return NotFound`: `();` is one token
  and a cut was allowed only after a word or a closing bracket); a `;` that ends the statement (`;`, `);`) is suggested after `return NotFound()`
  even with "Show suggestions that are only closing brackets" off, under the threshold after a dot (its confidence is split between `;` and ` ;`).
- NuGet window: the Prerelease box re-filters the versions of the selected package's card at once (the list reloaded, but the card kept the
  versions it had filtered for the previous state of the box while the same package stayed selected); the full version list is kept per
  package, so the toggle does not ask the feed again.

## 0.1.138

- Namespaces ordered by corpus statistics (engine e20, `ml-models/csharp/cs-imports-e20.cml`: for a type name, how often each namespace
  supplies it in open-source C# and which usings go together, 2.6 MB, ~100 ms to load, microseconds per query; top-1 0.814 with the
  file's usings vs 0.777 by frequency alone on the engine's test fold). Where it applies: the "Import type" quick fix and its popup — the
  namespaces the plugin's own resolution found for the unresolved type (`Task`: `System.Threading.Tasks` before `Microsoft.Build.Utilities`,
  `Color`: `UnityEngine` by frequency, `System.Drawing` once `System.Drawing.Imaging` is imported) are ordered by `rankImports(name,
  usings)` with the file's own `using`s, global usings and its namespace as the context, and the first one is what the fix adds in
  one step; the not-yet-imported rows of the completion list (`Widget (in Alpha)` / `Widget (in Beta)`) by the weigher
  `dotnetImportStats` after `priority` (the ML ranker's weigher runs before the priority and keeps the last word over the whole list).
  Attributes are looked up as `FooAttribute`; a name the corpus never saw keeps the index order (`System` first, then alphabetical);
  the statistics only order what the project and its assemblies really have, a namespace the index does not know is never offered.
  Decided against adding usings from the co-import statistics (`rankCoImports`): a `using` nobody asked for is noise, the context
  only ranks. Loading: application service `CSharpImportStats`, lazily on a pooled thread at the first query, from
  Settings | .NET | ML completion → model directory when set, else the plugin's resources (the ML build bundles the file), else
  `ml-models/csharp` of the working directory; while it loads or without the file the current order stays. Works in the plain
  build too. Settings | .NET → Behavior: "Order namespaces by corpus statistics" (on). Tests `CSharpImportStatsTest` (a stub model
  whose answer flips with the present usings, unknown name, setting off, the real artifact on stable names); scenario
  `Console/Editor/ImportType.cs` (`import-stats-*`)

## 0.1.137

- Grey text inside string and character literals is back ON by default (log and error messages, format and interpolated strings: the
  model continues them like code); only comments stay suppressed. The single setting of 0.1.136 became two: "Suggest inside string
  literals" (on) and "Suggest inside comments" (off); `CSharpNnInline.literalAt` tells the kind (`STRING` / `COMMENT`) the provider gates on

## 0.1.136

- GBDT ranker (engine e19): `ml-models/csharp/e19-rank-gbdt.cml` (200 trees, 100 KB) ships as `rank.cml` of the ML build instead of the
  linear `e18-rank.cml`: on 26 held-out repositories MRR 0.759 / top-1 0.651 vs 0.713 / 0.589 (plugin rules 0.530 / 0.370), 0.3 ms per
  list of 50 candidates on one core; `CSharpMlModels` loads any ranker through `Rankers.read` (linear or tree, by the `.cml` kind).
  ml-core synced to the engine 0bcfb9f
- No grey text inside string and character literals or comments: the provider looks at the PSI leaf at the caret (either tree:
  string, verbatim, raw and interpolated-string text tokens, `//`, `///`, `/* */`) before it asks the network, or at the host lexer's
  token while the document is not yet committed; right after the closing quote or `*/` and in a hole of an interpolated string it is
  code again, `// ⟨caret⟩` on an empty comment stays suppressed. Setting "Suggest inside strings and comments" (off) turns the gate
  off; the code-only confidence gate (`codeConfidence`) is unchanged — this one is in addition to it
- The thread of the network runs at a low priority (`MIN_PRIORITY + 1`) while it loads, warms up or prefills an opened file and at the
  normal priority while a grey-text request is queued or running (`NnThread`: raised by the request, lowered when none waits; no
  extra threads)
- A suggestion that is exactly what already follows the caret on the line, or that would make the line a copy of the previous one
  (the model repeating the line above: `a.Name = b.Name;` twice) is dropped (`CSharpNnInline.repeatsPreviousLine`); the engine's own
  guard sees only repetitions inside the generated text
- Tab with the completion list and the grey text both shown is arbitrated by the platform (checked on 2026.1.4: `InlineCompletionActionsPromoter`
  puts `InsertInlineCompletionAction` first while the grey text is shown and `InlineCompletionHandler.insert()` hides the lookup), so
  one Tab inserts the grey text only, never the list's item on top of it; no change needed

## 0.1.135

- The completion list learns from the user: every item chosen in a list of a C# file is counted per project and kind of place (after a
  dot, the start of a statement, an argument, a type, the right side of `=` — the kinds of the ML ranker's schema), in the workspace
  file of the project, halved every month. Without the ML ranker the chosen items go first among the rows of one priority group (the
  weigher `dotnetAcceptedBefore`, the rules' order between groups stays); with the ranker `0.3 × ln(1 + count)` is added to its score
  (the weight on Settings | .NET | ML completion: three choices win a close call, not a clear loss). The counts are recorded off the EDT
  and never read while the setting is off. Settings | .NET → Behavior: "Remember what I choose in the completion list", "Forget the
  Choices". What is stored and how: `ML_ACCEPTANCE.md`

## 0.1.134

- Mapping completion: copying the members of one object into another. In an object initializer (`var dto = new UserDto { | }`,
  `return new Dto { Id = user.Id, | }`) and at the start of a statement under a block of assignments to one target
  (`dto.Name = user.Name;`), every member the target still lets set gets a row with the value at hand whose name is like the member's
  and whose type converts to it — `Name = user.Name,` / `dto.Email = user.Email;` — from locals, parameters, the fields and properties
  of the enclosing type and their public members two deep (`user.Profile.Email`), plus one row "Map all remaining members from user"
  that writes every confident match at once in the order the members are declared, at the indentation of the line. Names are compared
  exactly, ignoring case, by a shared start or end (`UserId` ↔ `Id`) and by the overlap of their camel-case words; the object the
  neighbouring assignments copy from (the mapping partner) wins ties; a member whose best value is not clearly the best gets no row.
  `init`-only members are offered in initializers only, the required ones first, the set ones skipped. The rows come first when the
  context is clearly a mapping (an assignment with the partner already written, an empty initializer with an object at hand that fills
  two members), after the usual items otherwise; marked by a grey `map`. Pure PSI and the plugin's own semantics (the plain build);
  Settings | .NET → Behavior turns it off

## 0.1.133

- The " ML" mark on the ranked completion items is off by default (`CSharpMlSettings.showMarker`); Settings | .NET | ML completion turns it on.

## 0.1.132

- ML completion in a build with `-PmlEnabled=true` (`bash gradlew buildPlugin -PmlEnabled=true [-Pml.big=true]` → `idea-dotnet-support-<v>-ml.zip`;
  the plain build is unchanged): grey text to the end of the line in C# files from our own transformer `cs31m-e2-lr2e3` (31 M parameters,
  the engine of idea-ml-completion in `lib/ml-core.jar` with its native SIMD kernels, scalar fallback) while typing, while the completion
  popup is open or on an explicit call; Tab accepts. The confidence gate is 0.7 (14 % of positions, 94 % exact lines), 0.5 right after
  `.` / `?.` / `::` / `->`, 0.25 on a line just opened by Enter; a suggestion of closing brackets only is hidden (a setting shows it).
  Adopted from the Go plugin's live use (0.2.199–0.2.206): the provider is first and asks a host's own provider before the network, one
  KV-cache session per editor, the model copied once to the IDE system directory by SHA-256, the tail of a suggestion the rest of the
  line already has is dropped, the gate counts the code tokens only (the text inside a string literal is the guess, a setting), an
  uncertain line shows its longest certain start, every answer of the network can go to the plugin log (category `ml`)
- The completion list ordered by the ML ranker `e18-rank` trained on the plugin's own exported lists (MRR 0.71 against 0.53 of the
  rules): the native list is held back until every contributor has answered, each candidate gets the same features as the export
  (the n-gram model `e15-a` with the per-file cache, the 19 language features) through the same code — a parity test replays the
  export on its fixture and compares the vectors — and the weigher puts the score before the rule priority; the rows carry a grey
  `ML` mark (a setting); the plugin's own order stays while the models load and in a build without them
- Preload: the models load and warm up in the background when a project with C# files opens (after indexing; a project without `.cs`
  files pays nothing, the application start loads nothing), and every C# editor opened prefills the KV cache of its session with the
  file at the caret on the model's thread, so the first grey-text request there is incremental (~25 ms) instead of a cold prefill
- Settings | .NET | ML completion: the ranker and its mark, the grey text with its three gates, closers, string guessing, the big
  model `cs50m-e3-lr2e3` (when packed with `-Pml.big=true`), a directory with other models, the log of every answer, and what is
  loaded (models, kernels, shown / accepted counts)

## 0.1.131

- The headless export of completion lists for the ML ranker (`mlDataset`) is 7× faster per position (45 ms, was 334; 2 s on a
  solution of 500 types) and sees the libraries: the
  projects of a repository are restored (`dotnet restore`, with their MSBuild files fetched apart from the source corpus) and their
  assemblies indexed before the export, so the members of `string`, `List<T>` and NuGet types are in the lists after a dot (98 % of
  such positions get a list, was 61 %; recall 0.81, was 0.71); the type names of the solution are read from the stub index once per
  repository instead of once per position, and the debug log of the test framework, which formatted a time stamp on every index
  lookup, is off. A persistent system directory and one folder of assembly indexes for all workers (`-Pml.sandbox`, `-Pml.helpers`). Nothing
  changes in the editor: the per-file snapshot exists only while the export sets it

## 0.1.130

- Offline export of real completion lists for the ML ranker (Gradle task `mlDataset`, the plugin half of experiment e18 of the shared
  completion engine): the plugin's own completion runs headlessly over C# repositories, every sampled position records the list the
  IDE would show with the identifier of the source as the answer, and each candidate carries the features the ranker learns from — its
  kind, static or not, how far its declaration is (local, member, base, receiver, imported, not imported), whether a `using` is needed,
  whether it fits the expected type, the distance to its declaration and the plugin's own rule order. The same feature code will rank
  the list in the IDE once a model is trained on these exports. Nothing changes in the editor in this version

## 0.1.129

- The pass that gives every C# file of the project its `#if` symbols and language version (at startup, on another framework in the
  toolbar) no longer freezes the IDE: it checks for cancellation before every file, so a write action (a save, a VFS refresh) interrupts
  it instead of waiting for it with the UI, and it resumes where it was instead of starting over. Each file is normalized once and asks
  the project model once (was twice, and once per project of the solution). Seen live 2026-10-07: freezes of 7–19 s on a repository of
  31 projects and 530 files, every thread dump in this pass

## 0.1.128

- A `.cs`, `.csx`, `.sln`, `.slnx` or `.csproj` that opens as another file type (a user association made before the plugin was installed,
  typically Text, shadows the plugin's own and nothing of C# works in the file) gets a banner "This file opens as "Text", not as C#" with
  Associate with C# and File Types Settings…; a .NET project with such an association gets a modal dialog at startup and when such a
  file is opened, with the same fix ("Not Now" holds for the session, "Don't ask again" keeps the banner only)

## 0.1.127

- The gray text and the typing assistance of the native C# tree no longer log "Read access is allowed from inside read-action only"
  when the inline completion handler asks on the EDT (seen in the sandbox log): the tree is read under a read action
- A test that every test of a live `dotnet test` run starts under a suite that is still open, in every order the events come (the
  platform forgets a finished node and logs "Parent node is undefined" for a child that comes after it)

## 0.1.126

- `override` completion as in Rider: typing the start of the return type (`public override str`) keeps the members of that type
  (`string Describe()`, `string ToString()`), `public override string D` completes Describe over the typed type, and `struct`, `class`
  and other keywords no longer show after `override`
- From `public ov` the list offers `override string Describe` rows that write the whole member, and gray text shows the best member to
  override: Tab writes it with its `using` directives and puts the caret in the body

## 0.1.125

- The Project tool window reliably opens in the Solution view the first time a folder with a solution is opened: the switch used to
  race the creation of the tool window and was never retried when it lost. After the first switch the pane you choose is kept on reopen

## 0.1.124

- Code Vision: no "N usages" / "N implementations" / Run / Debug lenses over library sources opened via Source Link, decompiled types
  or the metadata view — only over the sources of the solution, as in Rider
- Shared frameworks a package requires (`Grpc.AspNetCore` → `Microsoft.AspNetCore.App`) are now part of what the project compiles
  against: their types resolve in completion, Go to Declaration and the compiler-error checks without a `FrameworkReference` or the Web
  SDK in the project file

## 0.1.123

- Source Link works: every download failed in 0.1.121 and 0.1.122, and the decompiled code opened instead
- Code Vision: every declaration of a line keeps its own lens entry — `int a, b;` reads "1 usage | no usages", a one-line enum shows
  the type and each member
- Code Vision: the counts of members below an edit in the file no longer drop to "no usages" until the file is reopened; the lenses of
  every open editor are recomputed when another file changes or indexing ends
- Find Usages: a constructor initializer `base(...)` / `this(...)` is a usage of the constructor only, not of the type, as Roslyn and
  Rider count it; "N usages" of a class agrees with Show Usages
- No "Read access is allowed from inside read-action only" error when clicking "N implementations" or the gutter of overrides with
  several targets

## 0.1.122

- The "Analysis" scopes of Settings | .NET | Language Server work without the language server: with "Compiler diagnostics for"
  fullSolution the Problems tool window (Project Errors) lists the errors and warnings of every file of the solution, checked in the
  background and updated as files change; openFiles lists the open files; none shows no compiler diagnostics at all, as Roslyn does
- "Analyzer diagnostics for" fullSolution analyzes whole projects on save and after a build and puts the analyzer warnings into the same
  tab; none hides them in the editor (Run Code Analysis still lists them)
- With the server on, only one provider fills the Project Errors tab: the server when "Errors and warnings" is Language server, the
  plugin when Built-in
- Every option of Settings | .NET | Language Server now works with the server off

## 0.1.121

- Go to Declaration on a type or member of a library opens its original source when the PDB of the assembly has Source Link or embedded
  sources (the option "Navigate to Source Link and embedded sources" of Settings | .NET | Language Server, now honoured without the
  server): the file is downloaded by the IDE's HTTP client, checked against the hash in the PDB, cached on disk and shown read-only with
  the banner "Navigated to source from Source Link: <url>"; without a PDB, a mapping or the network the decompiled code or the metadata
  view opens as before
- Source Link URLs are followed only over HTTPS to public hosts, without redirects and up to 16 MB: a PDB cannot make the IDE reach this
  machine or the local network

## 0.1.120

- Applying Settings | .NET | Language Server redraws the inlay hints and Code Vision of the open C# files at once: a toggled Inlay Hints
  or Code Lens option, or a switched source of "Inlay hints" / "Code Vision", no longer waits for the next edit
- Switching "Inlay hints" or "Code Vision" to the language server while it runs shows the server's hints and lenses immediately,
  without a restart of the server
- No false CS7036 on a named argument in its own position followed by positional ones (`Draw(width: 1, "t", shape: s)`, C# 7.2):
  the arguments are bound as Roslyn binds them

## 0.1.119

- Code Vision without the language server: "N usages" above every type and member, counted by the plugin's own Find Usages (a click
  opens Show Usages), "N implementations / overrides / inheritors" where there are any, and "Run | Debug" above test methods and test
  classes, running the same configurations as the gutter
- The Code Lens options "References" and "Run and debug tests" of Settings | .NET | Language Server govern the built-in lenses too; new
  switch "Code Vision" in Source of Features (Built-in by default); with Language server the server's lenses show instead, never both
- Usages outside the edited file are cached until another file changes, so typing in a large file recounts that file alone

## 0.1.118

- Every option of Settings | .NET | Language Server now applies to the built-in C# features as it does to the server: completion from
  unimported namespaces, name suggestions, regex completion, completion in argument lists, decompiled navigation, remarks in quick
  documentation, symbol search in reference assemblies, documentation comment auto-insert, regex / JSON highlighting in strings, the
  insertion location of generated members and throwing vs auto generated properties
- "Organize 'using' directives when formatting": Reformat Code with the built-in formatter removes unused directives and sorts the rest
  (`System` first unless `dotnet_sort_system_directives_first = false` in .editorconfig)
- Implemented interface and abstract properties throw `NotImplementedException` by default, as in Roslyn ("Generated properties: prefer
  throwing properties"); choose "prefer auto properties" for `{ get; set; }`

## 0.1.117

- Inlay hints without the language server: parameter names before arguments and the types of `var`, lambda parameters, `new()` and
  collection expressions, computed by the plugin's own semantics with Roslyn's rules; the Inlay Hints options of Settings | .NET |
  Language Server apply to them exactly as to the server's
- New switch "Inlay hints" in Source of Features (Built-in by default); with Language server the server's hints answer and the built-in
  ones stand back, never both
- A click on a type hint opens the type: a declaration of the solution or the metadata view of an assembly type

## 0.1.116

- Colored matching brackets in C# files: pairs of `()`, `[]`, `{}` and the `<>` of generic type lists are colored by nesting depth
  (three levels, VS Code's colors for light and dark schemes; Editor | Color Scheme | C# | Braces and operators | Matching brackets).
  Strings, characters, comments and inactive `#if` branches are left alone; interpolation holes count as code; an unmatched bracket stays
  plain and does not shift the rest of the file. Painted at once as a file opens
- Settings | .NET: "Colorize matching brackets" (on by default); switching it re-highlights the open editors

## 0.1.115

- Errors of statements without the language server: a value returned from a void method, an `async Task` or a lambda, or a missing one
  (CS0127, CS1997, CS0126, CS8030, CS8031), `return` in an iterator (CS1622), `await` outside an `async` method or lambda (CS4032, CS4033,
  CS4034, with Alt+Enter «Make method async» on it), `await` inside `lock` (CS1996)
- Switch and jump errors: duplicate `case` labels found by their constant values — enum members, constants, folded expressions (CS0152),
  falling through or out of a case (CS0163, CS8070), `break` / `continue` outside a loop (CS0139), `goto` to a missing label or case (CS0159),
  leaving a `finally` (CS0157)
- Error messages name types with their nullable annotations, as the compiler does

## 0.1.114

- Override errors: no member to override (CS0115), a base member that is not virtual or is sealed (CS0506, CS0239), a changed access
  modifier (CS0507, `protected internal` of libraries too) or return type (CS0508, CS1715) — against bases of the solution and of libraries
- Abstract and body errors (CS0513, CS0500, CS0501), deriving from a sealed or static type, a record or a class among interfaces (CS0509,
  CS0709, CS8864, CS8865, CS0527), `new` of an abstract type, interface or static class, target-typed `new()` too (CS0144, CS0712), a static
  member through an instance (CS0176), instance members in field initializers (CS0236)
- No more false «name does not exist» on a positional parameter passed to a record's base: `record Employee(string Name) : Person(Name)`

## 0.1.113

- Ambiguous calls (CS0121) and type arguments that cannot be inferred (CS0411), with generics, `params`, optional and `ref` parameters,
  extension methods and library types, named in the order the compiler uses
- Lambda errors: a wrong number of parameters (CS1593), a lambda where no delegate is expected (CS1660), mismatched parameter types or
  `ref` / `out` / `in` (CS1661, CS1678, CS1676, CS1677), a block body that does not return on every path (CS1643) — in overloaded and generic
  calls, `return`, property initializers and event `+=`
- Bad arguments (CS1503) are reported against the same overload `dotnet build` picks: derived types first, library methods in metadata order
- Errors on calls are no longer hidden by extension methods the file does not import (EF Core's `Like`, `Vector.Store`) or that cannot
  take the receiver (`Queryable.Take` next to `Enumerable.Take` on a `List<int>`)

## 0.1.112

- Operators and casts as `dotnet build` reports them: an operator that cannot take its operands (CS0019, CS0023 — `decimal * double`,
  `bool + int`, `!count`), an impossible cast (CS0030); silent wherever a user-defined operator or conversion may apply
- Statement forms and implicit typing: `count + 1;` (CS0201), a type or method used as a value (CS0119), a method group assigned to a value
  (CS0428), `var` with `null`, a void call, no initializer or an array initializer (CS0815, CS0818, CS0820), indexing what has no indexer
  (CS0021), `this` without an instance (CS0026, CS0027)
- Range indexing of spans (`span[1..]`) is typed as a span, not as its element

## 0.1.111

- Access errors across projects and libraries as `dotnet build` reports them: `InternalsVisibleTo` (MSBuild items and attributes, the
  friend's public key, `AssemblyName`), internal and private types and protected members (CS0122, overloads and constructors too)
- Read-only targets: properties and indexers without a reachable setter (CS0200, CS0272), getters (CS0154, CS0271), readonly and static
  readonly fields (CS0191, CS0198), init-only properties (CS8852), something that is no variable (CS0131), readonly fields, properties and
  indexers passed by `ref` / `out` (CS0192, CS0199, CS0206)
- The index of assemblies (format 4) keeps internal and private types, internal members and the accessibility of accessors; the indexes are
  rebuilt once

## 0.1.110

- `Use of unassigned local variable` (CS0165) and `Use of unassigned out parameter` (CS0269) with the compiler's flow rules: branches, loops,
  `try` / `finally`, `&&` / `||`, `out` arguments, `is` patterns and lambdas; an `out` parameter left unassigned on a `return` or at the end
  of the method (CS0177)

## 0.1.109

- Duplicate declarations: a local, local function or parameter declared twice (CS0128, CS0100), a local reusing a name of an enclosing scope
  (CS0136), LINQ range variables (CS1930, CS1931), a local used before its declaration (CS0841, CS0844)
- Duplicate members and types: the same name twice (CS0102), the same parameter types (CS0111), overloads differing only by `ref` / `out` /
  `in` (CS0663), conversions (CS0557), partial methods (CS0756, CS0757), interface members implemented twice (CS8646), partial parts with
  other type parameter names (CS0264), a type declared twice in a namespace — across files and folders, in the compile order of MSBuild (CS0101)

## 0.1.108

- Type arguments that break the constraints of a generic type or method (CS0311, CS0315, CS0452, CS0453, CS0310), written or inferred,
  for types and methods of the solution and of libraries; the wrong number of type arguments (CS0305, CS0308)
- Every compiler error of the plugin has a file in `debug-playground/Broken/Errors` with its lines marked `// ERROR CSxxxx`;
  `tools/diag/check_errors.py` compares the marks with `dotnet build`, the plugin in the IDE and roslyn-language-server

## 0.1.107

- No need to type `=`: after `Console.BackgroundColor ` (a settable member and a space) the list opens by itself with
  `= ConsoleColor.Black`… for an enum, or `= name`, `= dto.Name` for other values, and Enter writes the assignment with its `;`
- Generic extension methods respect the constraints of their `this` type parameter: `AddEndpointFilter` (`where TBuilder : IEndpointConventionBuilder`)
  is no longer offered after `day.`, and calling it on such a value is an error (CS1061)

## 0.1.106

- The completion list opens by itself after `= ` and `return ` where an enum is expected (`Console.BackgroundColor = `), as after `==` and `case`
- An enum member chosen at the end of a line closes the statement: `Console.BackgroundColor = ConsoleColor.Black;`, `Take(Status.Paid);`,
  `if (status == Status.Paid)` — also after `ConsoleColor.`; nothing is added when the line goes on or inside an initializer

## 0.1.105

- Ctrl+Click goes on inside decompiled code: the names of a decompiled type resolve against a project compiled against its assembly
  (else the project with the most references), so a type or call in it opens the next decompiled type

## 0.1.104

- Gray text no longer offers a variable name after a member of a type: `JsonSerializer.Serialize ` showed `serialize`, `Console.Out `
  showed `out`, as if they were nested types; a nested type (`Json.Options `) still gets its name

## 0.1.103

- Gray text no longer offers a variable name after a member of a value: `member.Ad` with `Admin` selected in the list showed `min admin`,
  as after a type; now it shows `min = isAdmin;` — the member and the value at hand for it
- `member.Email = ` (a statement) gets the value at hand as gray text with `;`, the properties of the values around included (`dto.Email;`);
  never the member itself
- The completion list at `Name = ` of an initializer and at `member.Name = ` has rows with the paths into the values at hand
  (`dto.Name`, `dto.Email`), best first
- No IDE error ("Read access is allowed from inside read-action only") from the gray text when the completion list closes
- The list puts the path rows (`dto.Name`) under the value the gray text gives (`name`), so both say the same; `Name =` without a space gets ` name;` with one

## 0.1.102

- Gray text while the completion list is open: it follows the selected row and shows what choosing it gives — `new ` with `Member`
  selected → `Member();`, `Draft(Mem` with `MemberDto` selected → `berDto memberDto`, the value of `Name = ` when the selected row begins
  it. Tab takes the row with the gray text and closes the list; Enter takes the row alone, as before
- `new Membe` lists `Member { … }` right under `Member` for a type created without arguments that has members to set: choosing it writes
  the object initializer, a member a line, the required ones first, each with its value when one is at hand (`Name = name`); Tab goes
  from value to value. Shown for the types whose name is typed (two letters at least), for the type the variable names
  (`var member = new `) and for the expected type
- `new OrderLine` of a type with required members fills their values from the variables at hand too, with the same Tab stops

## 0.1.101

- More gray text while typing, by rules (no machine learning), Tab takes it:
  - `var user = new ` → `User();`, the type named as the variable (`var users = new ` → `List<User>();`), when it is created with no
    arguments and has no required members
  - `new User(|)` → a gray `;` after the `)`; Tab writes `new User();`. Also after the `}` of an initializer written over several lines
  - on the empty line of `new User() { }`: the members a line each, the required ones first, with their values when they are at hand
    (`Name = userDto.Name,`)
  - `Name = ` in an object initializer → the variable, parameter or member of that name and a fitting type (`name`), or a property of
    one (`userDto.Name`); no `;` there any more
  - a name after a type: `UserDto ` → `userDto`, `List<User> ` → `users`, `IUserService ` → `userService`, `_userDto` for a private
    field, also in `foreach`
- After `class ` (`record`, `struct`, `interface`, `enum`) completion offers the name of the file; choosing it writes the body too
- `public clas` lists `class` once: the live template of that name is no longer next to the keyword

## 0.1.100

- Alt+Enter on a name that does not resolve offers Rider's "Create …" quick fixes: "Create class / record / struct / interface / enum 'Foo'"
  (a new file next to the current one, in its namespace; `new Foo(1, name)` gets a constructor with those parameters, `Foo.Bar` an enum
  with `Bar`, a name in the base list an interface), "Create field / property / local variable / parameter 'x'" typed from the usage, and
  "Create method 'M'" for a call (its parameters from the arguments, `void` / `Task` from the place); `x.Missing` creates the member in
  that type of the solution
- Implement missing members keeps the default values of parameters (`CancellationToken ct = default`, `= null`, `= 0`) and their
  attributes (`[CallerMemberName]`): calls without those arguments no longer fail with CS7036 at the build
- CS7036 ("There is no argument given that corresponds to the required parameter …") is shown before the build for a call of a class
  whose method lacks a default value its interface has, and for `new T(…)` of a type of the solution; CS1729 when no constructor takes
  that many arguments
- Reformat Code lays out code written on one line as Rider's default style does: `class A { void M() { x(); } }` gets its braces and
  statements on lines of their own; accessors, lambdas, enums and initializers stay on their line, and `csharp_preserve_single_line_blocks`
  in `.editorconfig` still keeps blocks as they are
- `;` typed inside the parentheses that end a statement goes after them (`new Repository(|)` + `;` gives `new Repository();`), and `)`
  after a string steps over the one the editor put
- Enter after an enum written on one line (`enum Status { New, Paid }`) keeps the indent of the declaration
- Postfix templates are not offered after a type name (`OrderStatus.` lists the members only); `.new` and `.typeof` stay for classes
- New → Class/Interface → Record puts the caret inside the `()` of the record
- In `appsettings*.json` a `,` typed right before the comma a completion has put steps over it instead of writing `,,`
- The welcome page no longer logs an IDE error ("JBCefApp$Holder <clinit> requests ProxyMigrationService") when it is the first embedded browser of the session.

## 0.1.99

- New Solution on SDK 10: the templates are no longer all under "Not supported for the selected Target Framework" and Create works again.
  The frameworks of a template are read from the choices of `--framework` as well as from its option line, and a help that cannot be read
  means "any framework", never "none"
- "Manage NuGet Packages..." of a project no longer fails with a NullPointerException when the NuGet window opens for the first time
- The NuGet window asks the password storage only about feeds that need credentials (stored by the plugin, or after a 401 / 403 of the
  feed), once per session, and never about nuget.org: on Linux without a keychain every search used to show "IDE error occurred"
- The notification of a failed build before a launch says the launch "was not started"
- "Move to Solution Folder..." on projects of the Solution view, and dragging projects onto a solution folder or the solution moves them
  there, as in Rider; dragging no longer logs "Access is allowed from EDT only"
- Add | New Project... is the window of New Solution: the same kinds, template names and options; options of a template show only when
  they apply (the Azure AD fields of Web API with an authentication that uses them)
- After Add | New Project... the new project is selected and expanded in the Solution view

## 0.1.98

- Object initializers and C# 11 `required` members, as in Rider: choosing `new OrderLine` from completion for a type with required members
  writes the initializer with them (`{ Sku = |, Title = }`, one member a line, the caret at the first value) instead of `()`; a type
  whose constructors all take arguments keeps `new T(|)`. The row shows the members: `OrderLine { Sku, Title }`
- CS9035 ("Required member 'OrderLine.Sku' must be set in the object initializer or attribute constructor") is shown without the language
  server, for the types of the solution and of the referenced assemblies; a constructor marked `[SetsRequiredMembers]` sets them all.
  Its quick fix "Add initializer for required members" writes the missing ones
- Inside `new T { | }` completion offers "Fill required members" and "Fill all members"; choosing a member writes `Name = `
- Alt+Enter in an object initializer: "Initialize members" and "Initialize required members"

## 0.1.97

- C# color palettes: Rider, Visual Studio, VS Code, Nord, Dracula, One Dark / One Light, Solarized and GitHub colors for C# on top of the
  color scheme you use — the background and every other language stay as they are, and the dark or light variant follows the background
  of the scheme (also when the theme is switched)
- Choose it in Settings | .NET ("C# color palette") or in .NET → C# Color Palette…, which previews each palette in the open editors while
  you move through the list (Esc puts the previous one back); "IDE default" returns the scheme's own C# colors
- The first C# file opened with a scheme that leaves C# uncolored offers the palettes once ("Don't Show Again" silences it)

## 0.1.96

- Double completion, as in Rider and IntelliJ: the second Ctrl+Space shows what the first one leaves out, and the first one says so in the
  line at the bottom of the list ("Press Ctrl+Space again to show…")
- After a dot, the second press adds the members the place does not see (private, protected and internal of other types, protected members
  of library types), grayed with "(not accessible)" at the bottom; choosing one writes it as it is, the compiler's error is then yours
- Where a type may stand, the second press adds the types of the packages (and the outputs of projects) that other projects of the solution
  reference and this one does not, as `Name (in Namespace, Package 1.2.3)`, from the first letter typed; choosing one adds the `using` and
  offers to add the package or the project reference in a balloon (`dotnet add package` / `dotnet add reference` run by its button only)
- The second Ctrl+Shift+Space adds the chains: `order.Customer`, `ledger.Count` — a member of a local, a parameter or a member of the
  enclosing type whose value is of the expected type, one access deep, with `()` for a parameterless method

## 0.1.95

- MSBuild files: `$(` completes property names (those of the file, of the files it imports and of `Directory.Build.props` / `.targets` / `Directory.Packages.props`
  above it, the ones MSBuild knows, the ones of the schema), `@(` item types, `%(` metadata (`%(Item.` of that item); the parenthesis is closed
- MSBuild files: file paths in `<Import Project="…">` (MSBuild files) and `<ProjectReference Include="…">` (project files), `..\` and `$(MSBuildThisFileDirectory)` included
- `[assembly: InternalsVisibleTo("` lists the projects of the solution; `extern alias ` the aliases of the references of the project; `delegate* unmanaged[` the calling conventions
- `#:package ` in a file-based app lists the package ids of the feeds (after `@` their versions); `#:` lists the directives
- New setting Settings | .NET → "Exclude from completion": types and namespaces (`System.Data.*`) the C# completion never offers, also not imported ones and their extension methods
- Live templates `hal`, `ua`, `rta`, `ctx` for ASP.NET Core controllers
- Format specifiers are listed for a format that starts with a digit (`$"{x:0`)

## 0.1.94

- Explicit interface implementation: after `void IFoo.`, `int IFoo.` or `IFoo.` at the start of a member the list offers the members of `IFoo`
  that are not implemented explicitly yet and writes the whole signature with a body; `void ` itself offers the names of the implemented
  interfaces, which write `IFoo.` and open the list
- `[]` after the dot of an array, a string, a list, a dictionary or a type with an indexer (shown as `this[int index]`): it turns `x.` into `x[|]`
- Names of tuple elements after the dot (`pair.Title`), and names for the variables of a deconstruction (`var (title, count) = pair;`,
  `foreach (var (a, b) in pairs)`) taken from the tuple, from `Deconstruct` or from the positional record
- `partial class |` (struct, record, interface) offers the partial types of the same namespace that have a part in another file

## 0.1.93

- Message templates of logging: in `logger.LogInformation("Order {OrderId}", …)` (every `Log…` method, `BeginScope`), Serilog's
  `Log.Information(…)` and `[LoggerMessage(Message = "…")]` the placeholders have the color of format items, as in Rider; after `{` the
  list offers names made of the arguments (`order.Id` → `OrderId`, `Id`; the argument of that placeholder first) or the parameters of the
  `[LoggerMessage]` method, and Ctrl+Space in the text offers `{Name}` for an argument no placeholder takes yet
- A warning when the number of arguments does not match the placeholders of a message template (as CA2017), on the placeholder without an
  argument or on the argument without a placeholder; in `[LoggerMessage]`, on a placeholder without a parameter of the method
- Route templates of `[Route]`, `[HttpGet]`…`[HttpOptions]` and of `MapGet` / `MapPost` / `MapGroup`…: braces, parameters and constraints
  colored; after `{` the parameters of the action or the handler (not services, `CancellationToken`, `[FromBody]`), after `:` the route
  constraints (`int`, `long`, `guid`, `alpha`, `minlength()`, `range()`, `regex()`…), after `[` `controller` / `action` / `area`
- JSON in strings after `// lang=json`, in arguments of `[StringSyntax(StringSyntaxAttribute.Json)]` parameters and of `JsonDocument.Parse`,
  `JsonSerializer.Deserialize`, `JObject.Parse`…: JSON colors, errors and Edit JSON Fragment (where the IDE has the JSON plugin)
- Configuration keys: `configuration["…"]`, `GetSection("…")`, `GetValue<T>("…")` list the keys of the project's `appsettings*.json`
  (nested ones as `Section:Key`, relative to a `GetSection` they are asked of), `GetConnectionString("…")` the connection strings
- `services.AddScoped<IService, ` (`AddTransient`, `AddSingleton`, `TryAdd…`, `AddKeyed…`) lists the implementations of the service from the
  solution first, with the `using` of their namespace
- The list opens by itself after `{` of a template, `:` of a route parameter, the quote of a configuration key and `AddScoped<IService, `

## 0.1.92

- A method chosen from the completion list with `.` or `;` gets its call: `Total().` (the members of the result open), `Save();`,
  `Register(|);` with the caret in the parentheses when it takes arguments; the only item inserted by itself gets `()` / `<>` as with Enter
- Extension methods from assemblies show their types with what the receiver gives: `ToImmutableArray()` of a `List<Order>` returns
  `ImmutableArray<Order>`, not `ImmutableArray<TSource>`; parameter info writes `Func<Order, bool> predicate`
- A lambda place is found by the parameter's type, so a delegate with any name (`delegate bool Rule(Order o)`) opens the list with the
  lambda and keeps the typed parameter name, not only `Func`, `Action` and `...Handler`
- In `catch (` only types deriving from `Exception` come first: an enum or a class with a short name (`Ex`) no longer sits among them

## 0.1.91

- The completion list remembers what you choose: an item chosen before goes up among the items of its kind, while the order of the kinds
  stays as in Rider (locals, members, types, keywords)
- Commit characters: `.`, `,`, `;`, space, `=`, `[`, `)` and `(` take the selected item of a list that opened by itself and are typed
  after it (`cou` + `;` → `counter;`); not with nothing typed, not for an item that matches only in the middle
- Suggestion mode where a new name is written (`foreach (var `, `out var `, a name after its type, a lambda's parameter): the list shows
  names without selecting one, so Enter and space keep what you typed
- The list opens by itself after `#` at the start of a line, `<` of type arguments, `(` / `,` where a lambda may go, `== `, `case `,
  `[` of an attribute, and after `new `, `using `, `override ` with the language server off
- Quick Documentation (Ctrl+Q) of an item of the completion list, and the documentation popup that follows the selection
- Keywords as Roslyn recommends them: `and` / `or` after a pattern, `when` in a switch, `with` / `switch` / `is` / `as` after an
  expression, `get` / `set` / `init` and `add` / `remove` in accessor lists, `field` in a property accessor, `allows`, `extension` in a
  static class, `assembly:` / `module:` in a file's attribute list, `managed` / `unmanaged` after `delegate*`
- `nameof(` lists names only, `typeof(` types only (no `dynamic`); from three letters on, items that contain what you typed in the middle
  are listed under those that start with it (`ReceiptLi` → `WriteReceiptLine`)

## 0.1.90

- Completion inside interpolated strings works in holes of every kind (`$"{order.}"`, `$@"…"`, raw `$$"""{{…}}"""`): names, and
  members after a dot; the text of a string stays without a list
- Format specifiers: after `{value:` in an interpolation, `{0:` in `string.Format` / `Console.WriteLine` / `AppendFormat`, inside
  `ToString("…")` and the format of `DateTime.ParseExact` the list offers the formats of the value's type with examples, as in Rider:
  numbers (`N2`, `C`, `P`, `X`, `D`…), dates and times (`d`, `D`, `t`, `T`, `yyyy-MM-dd`, `HH:mm:ss`, `o`, `s`, `u`…), `TimeSpan`,
  `Guid`, enums
- Regular expressions in strings: the patterns of `new Regex(…)`, of the static `Regex.IsMatch` / `Match` / `Replace` / `Split`…,
  `[GeneratedRegex]`, `[RegularExpression]`, parameters marked `[StringSyntax(StringSyntaxAttribute.Regex)]` and strings after a
  `// lang=regex` comment are highlighted as regular expressions, with completion (`\d`, `(?<name>`), brace matching, inspections and
  Check RegExp of the IDE; .NET named groups `(?<name>…)` / `(?'name'…)` are understood
- Preprocessor directives: `#` at the start of a line lists the directives; `#if` / `#elif` / `#define` list the symbols of the
  project (its `DefineConstants`, the symbols of every target framework, `#define`s of the file); `#nullable` and `#pragma warning`
  complete their arguments, `#pragma warning disable` the warning codes with titles
- XML documentation comments: `<` lists the tags and writes their closing part, `</` closes an open tag, `<param name="` offers the
  parameters not documented yet (`typeparam`, `paramref`, `typeparamref` likewise), `cref="` the members and types the place sees

## 0.1.89

- Postfix templates look at the type of the expression, as in Rider: `.await` is offered on tasks and awaitables only, `.foreach` on
  collections, `.for` / `.forr` on collections and numbers (`for (var i = 0; i < orders.Count; i++)`, `.Length` of an array or a string),
  `.if` / `.else` / `.while` / `.not` on `bool`, `.null` / `.notnull` not on value types that cannot be null, `.using` on disposables,
  `.lock` on reference types, `.throw` on exceptions. When the type is not known, every template is offered as before
- `.var`, `.foreach` and `.using` name the variable after the expression and its type (`order.Total.var` → `orderTotal`, `GetOrders().var` →
  `orders`, `orders.foreach` → `order`) in a template box with the other names to choose from; a name already used nearby gets a number
- New postfix templates of Rider: `.field` and `.prop` (introduce a field or a property for the expression), `.to` (assign to …), `.arg`
  (wrap into a call), `.sel` (select the expression), `.parse` / `.tryparse` on strings with a list of target types, and `.inject` typed
  between the members of a type (`IOrderService.inject`: a primary constructor parameter, or a constructor parameter and a field). The
  templates have Rider's descriptions; statement templates are no longer offered between members
- Live templates grow to Rider's set (69 instead of 33): `ctorf` / `ctorp` (constructor initializing all fields / properties), `itli`,
  `itar`, `ritar`, `sfc`, `outv`, `out`, `asrt`, `asrtn`, `psvm`, `sim`, `~`, `indexer`, `iterator`, `iterindex`, `equals`, `Attribute`,
  `Exception`, `namespace`, `#if`, `#region`, `checked`, `unchecked`, `unsafe`, `pci`, `pcs`, `psr`, `ear`, `nguid`, `from`, `join`, `mbox`,
  `propdp` / `dependencyProperty`, `attachedProperty`; descriptions as in Rider. `foreach`, `itli`, `from` name the element after the
  collection (`orders` → `order`)

## 0.1.88

- C# completion knows the expected type, as in Rider (built-in completion, no language server needed):
  - object, collection and `with` initializers list only the members that can still be assigned (`new Order { Id = 1, |` → `Customer`,
    `Status`…, not `Id`, read-only or `private set` ones); property patterns (`order is { |`, nested `{ Buyer: { |`) list the members of
    the matched type
  - the members of an expected enum come first as `OrderStatus.Paid` rows with their values: after `status == `, `case `, in a
    `switch` arm, after `is `, in arguments, assignments and `{ Status: `; the list opens by itself after `== ` and `case `
  - `await Highlights` rows for a method or local of `Task<T>` whose result is wanted; choosing one writes `await` and makes the method `async`
  - `new` with a target type: `Order o = new ` offers `Order()` first, then the derived types; `throw new ` only exceptions (of the
    solution and of the imported namespaces), `catch (` exceptions first; a base list offers classes and interfaces, `event ` delegates,
    a constraint classes and interfaces
  - smart completion (Ctrl+Shift+Space) lists only what fits the expected type: locals, members, methods returning it, static members of
    the type itself (`String.Empty`), enum members, `await` rows, `new T()`, `null` / `default` / `true` / `false`; where the type is
    unknown it is the usual list

## 0.1.87

- Completion without the language server offers the types of the referenced assemblies in the namespaces the file sees, with nothing
  typed too: `Li` or Ctrl+Space on an empty line gives `List<>` where `System.Collections.Generic` is imported, by a `using`, the implicit
  usings of the SDK or a `global using`. Types of the solution imported only by a `global using` are offered as well
- Types that are not imported, from the first letter, as in Rider: `Receipt (in Shop.Billing)` for a class of the solution in a
  neighbour namespace, `StringBuilder (in System.Text)` for one of the framework or a package. Choosing one adds the `using`; when the file
  already sees another type of that name (`Timer` of `System.Threading`), the namespace is written in front of it instead
- Extension methods of namespaces that are not imported after a dot (`numbers.ToImm` → `ToImmutableArray() (in
  System.Collections.Immutable)`), of the assemblies and of the solution, only those that take the value left of the dot; choosing one
  writes the call and adds the `using`
- Attributes of the assemblies at `[`: `[Obs` gives `Obsolete` and `ObsoletedOSPlatform (in System.Runtime.Versioning)`

## 0.1.86

- Completion in an argument list works without the language server again (it is off by default since 0.1.76, and these features came
  only from it):
  - choosing a method in the completion list opens its parameter info by itself, with the gray text of the arguments at hand
  - a lambda where a delegate is expected, first in the list and as gray text after `(` / `,`: `items.Where(` offers `x => ` and
    `(x, i) => ` of the second overload, named after the parameter types as in Rider; delegates of the solution are named by their
    parameters
  - `Changed += ` offers `(sender, e) => {}` and "Create method OnChanged(object?, EventArgs)", which adds the handler to the class;
    the same at a variable of a delegate type (`Func<int, bool> filter = `)
  - named arguments: `Place(qu` offers `quantity:` first; after positional arguments the remaining parameters of the overloads that fit;
    in an attribute the constructor parameters and the settable properties (`[Obsolete(DiagnosticId = `)

## 0.1.85

- Writing an override without the language server works as in Rider: after `override ` (or `public override `) the list opens by
  itself with the members of the base classes that can still be overridden — of the solution, of assemblies (`BackgroundService`,
  `Exception`, `object`) and of the C# the build generates (`Greeter.GreeterBase` of Grpc.Tools). The chosen member is written whole:
  the base's accessibility, the types as the file names them (no `global::`), `return base.X(...)` or `throw new NotImplementedException()`,
  `await` when `async` is typed, the `using` directives it needs
- Missing members are red without the server: CS0534 (an abstract member of the base class) on the class name and CS0535 (an interface
  member) on the interface in the base list. Alt+Enter there — and on an empty line of the class body — offers "Implement missing
  members"; "Override members..." is on Alt+Enter on whitespace of the body and on the header of a class with a base class. Ctrl+O and
  Ctrl+I open the same dialogs
- `base.` lists the members of base classes from assemblies too (`base.StartAsync` in a `BackgroundService`)
- Parameter Info (Ctrl+P) works in `: base(...)`, `: this(...)` and in the base of a primary constructor
- Generate → Overriding members no longer adds `using System;` to a member that does not throw
- The Override Members dialog shows one node per base type, and `override ` lists the members of the real base before those of `object`

## 0.1.84

- Colors of a C# file are there as it opens: identifiers, inactive `#if` text and format items no longer appear half a second after
  the keywords and strings. A file opened again shows the colors it had when it was closed at once (before the first paint, while its
  text is the same); a file opened for the first time gets them in about 0.15–0.25 s instead of 0.45–0.9 s, computed right away and
  reused by the editor's analysis instead of being computed twice; the analysis that follows changes nothing on screen. While the IDE
  indexes a project that has just opened, the file gets the plain colors at once and the full ones as soon as the indexes are ready
- The colors of identifiers are computed once per change: the editor's repeated passes over an unchanged file reuse them

## 0.1.83

- No false "CS1061: does not contain a definition" on members inherited from a base class of the solution when the file that uses them
  imports another type of the same name as the base (an EF Core entity `DirectStressTest : StressTest` queried in a handler that imports a
  DTO `StressTest`): the base is the one the entity's own declaration sees, as in the compiler. Quick Documentation and Go to
  Declaration of such members find them in that base

- C# colors follow the color scheme of the IDE: the plugin no longer puts its own colors into the IDE's schemes, so C# kinds take the
  scheme's Language Defaults (class, interface, function declaration and call, static method and field, constant, metadata) and edits of
  them reach C#, in Dark, Islands Dark, Light and any other scheme alike. Rider's palette is a scheme to pick: Settings | Editor |
  Color Scheme | "Rider Dark" or "Rider Light"

## 0.1.82

- Analyzer results without the language server are as quiet as in Rider and Visual Studio: the editor shows warnings and errors at the
  severity the compiler gives them (`.editorconfig`, rulesets, `AnalysisLevel`, `AnalysisMode`), while suggestions (IDE0290, CA1859…) are
  no longer underlined — their fixes stay on Alt+Enter. "Show suggestions" (Settings | .NET | Analyzers and Generators, now off by default)
  brings them back as weak warnings. On the playground: 3 marks instead of 174. `RunAnalyzers=false` is respected
- Run Code Analysis groups its results in the Build tool window by severity: Errors, Warnings, and Suggestions collapsed
- Find Usages and Rename without the server find what the compiler calls implicitly: `Deconstruct` (deconstruction, `foreach (var (a, b) …)`),
  `Add` (collection initializers), `GetEnumerator` (`foreach`), `Dispose` (`using`) and `GetAwaiter` (`await`), as Roslyn does; Rename
  leaves those places alone
- Find Usages of a type finds target-typed `new(…)` in dictionary and object initializers (`["a"] = new(…)`), collection initializers and
  collection expressions
- C# that MSBuild targets write into `obj/` — WPF XAML (`*.g.cs`, `*.g.i.cs`), gRPC (Grpc.Tools), resources — is known without the server:
  taken from the design-time build of the helper, or from `obj/` after the last build for projects of the old format. Such files appear
  under Dependencies → Analyzers → "Generated by build" and open with a banner. WPF and gRPC projects now show their errors instead of
  staying silent; after a XAML, proto or resx file is saved, the generated files are marked out of date until they are made again
- Names qualified by a namespace alias (`pb::IMessage<T>`, as protoc writes them) resolve; members of WPF types (TextBlock, Window…) get
  their errors — a non-public interface of a library type no longer makes the whole type unknown

## 0.1.81

- Extract Method inside loops: a variable the selection writes and the next iteration reads (in the loop's condition, before the
  selection or in the selection itself) is returned; a variable written on some paths only is passed in as well; `break`, `continue` or
  `return;` leaving the selection becomes `if (NewMethod(…)) break;` (or `NewMethod(…); break;` when every path leaves), as in Rider. What
  C# cannot express is refused with a hint: two kinds of jumps, a jump together with a value to return, `return` with a value
- Introduce Parameter (Ctrl+Alt+P, Refactor This → Introduce Parameter...) without the language server: the selected expression becomes
  a new parameter of the method or constructor, and every call in the solution passes it, with the parameters it reads replaced by the
  call's own arguments (`Twice(a + b, (a + b) * 2)`); a constant can stay as the default value of an optional parameter instead. The
  name is edited in place. Refused with a hint: locals in the expression, a method that overrides or is overridden, members of the
  instance when a call goes to another object
- Introduce Field: the name of the new field is edited in place at the field and at its uses, as Introduce Variable does
- Alt+Enter "Convert '?:' to 'if' statement" on a `?:` deeper in a statement (an argument, an operand): the statement is repeated in
  both branches; for the value of a local, the local is declared before the `if`
- Alt+Enter "Introduce variable" with several equal expressions in the block: Rider's chooser "Replace this occurrence only" / "Replace
  all N occurrences"
- Generate (Alt+Insert): Delegating members (first the field or property, then the members of its type), Equality comparer (a nested
  `IEqualityComparer<T>` class and a static property), Relational members (`IComparable<T>`, optionally `IComparable` and the operators
  `<`, `>`, `<=`, `>=`) and Relational comparer (a nested `IComparer<T>`), in Rider's order of the list. The server's "Introduce
  parameter for …" and "Generate comparison operators" rows no longer stand beside the plugin's own ones

## 0.1.80

- Nullable flow analysis on the plugin's own semantics (no language server): the null state of every local, parameter and member path
  (`x`, `this.F`, `x.A.B`) follows assignments, `== null` / `is null` / `is not null` / `is { }` / `is T t`, `??`, `??=`, `?.`, `!`,
  `&&` / `||`, early `return` / `throw`, loops, `try` and `switch`. New warnings as in Roslyn: CS8602 (dereference of a possibly null
  reference), CS8601 (possible null assignment), CS8604 (possible null argument, with the method in the message); CS8600, CS8603 and
  CS8625 now follow the flow instead of only literal `null`, and CS8618 is reported on constructors that leave a non-nullable field or
  auto-property unset on some path (`required`, `[SetsRequiredMembers]`, `: this(...)` and `[MemberNotNull]` helpers understood; partial
  types once their source generators are known)
- The attributes of `System.Diagnostics.CodeAnalysis` are understood in your code and in the referenced assemblies (`[NotNullWhen]`,
  `[MaybeNullWhen]`, `[NotNull]`, `[MaybeNull]`, `[AllowNull]`, `[DisallowNull]`, `[NotNullIfNotNull]`, `[DoesNotReturn]`,
  `[DoesNotReturnIf]`, `[MemberNotNull]`, `[MemberNotNullWhen]`): `string.IsNullOrEmpty`, `TryGetValue`, `ArgumentNullException.ThrowIfNull`
  and the like no longer leave a value "maybe null". Code compiled without nullable annotations is never warned about. The assembly index
  format changed (version 3): assemblies are indexed again once
- Precision first: where the analysis is not sure (a call it does not know, a lambda's captured variables, a type parameter) it stays
  silent. On the semantic gate (the playground and 17 libraries of dotnet/runtime, 887 files) it reports no warning Roslyn does not
- LINQ query expressions are translated into the method calls they stand for (C# spec 12.20.3) and resolved against the real type of the
  source: `Queryable` with `Expression<Func<...>>` for an `IQueryable<T>` (EF Core `DbSet<T>` included), your own `Select` / `Where` /
  `SelectMany` / `Join` / `GroupBy`, instance or extension. Range variables, `let`, `join ... into`, `group ... into` and continuations get
  the types the real methods give: completion, Quick Documentation (`(range variable) Order o`) and errors inside queries follow them
- Method-syntax calls on an `IQueryable<T>` pick `Queryable` over `Enumerable` (the better receiver), so lambdas against
  `Expression<Func<...>>` are typed and the result stays `IQueryable<T>`

## 0.1.79

- Analyzers and source generators of a project (and the design-time build behind them) no longer run for a project opened in Safe Mode
  (not trusted): they are code of the project

- Compound runs as in Rider: .NET Project configurations started together (a compound configuration, Run / Debug N Projects, or several
  started one right after another) are built by one build before any of them starts — the projects of a solution as one solution-filter
  build in the Build tool window, shared libraries once — and then run with `dotnet run --no-build`, so their builds no longer fight over
  `obj/`. If the build fails, none of them is started. On the playground the build of two services sharing a library took 1.9 s instead of
  3.6 s one after another
- Save as Compound Configuration: offered after Run N Projects, in the Solution view popup for several projects and in the Run menu (the
  .NET configurations running now). The compound is named after the projects (`Web + Worker`, or "Multiple Projects"), missing .NET Project
  configurations are saved, and they go into a folder of the same name, so the Services tool window groups them and stops or reruns them together
- "Wait for" in the .NET Project configuration: start after another configuration has started, listens on its address (from
  `launchSettings.json` or its output) or answers on a health URL, like `WaitFor` of .NET Aspire; with a timeout, a progress status while
  waiting, a notification when it runs out, and an error for a configuration that waits for itself or in a circle
- Debug of a compound configuration of .NET Project ones: it could not be started at all in IntelliJ IDEA Community (no runner of the
  platform took it); now the projects are built once and a debug session starts for each

## 0.1.78

- Overload resolution of the plugin's own C# support as in the compiler: implicit conversions (numeric, nullable, reference, boxing,
  user-defined), the better function member, `params` in normal and expanded form, optional parameters and named arguments, generic
  type inference, extension methods, lambdas and method groups against `Func` / `Action` / own delegates, target-typed `null`,
  `default`, `new()`, `?:` and collection expressions. Go to Declaration, quick documentation, colors and the types of `var` pick the
  right overload where they used to give up or pick the first one
- More compiler errors and warnings without the language server, with Roslyn's codes and places: unreachable code (CS0162, gray as in
  Rider), unused locals (CS0168 / CS0219, gray, "Remove unused variable"), a task not awaited in an `async` method (CS4014, "Add
  'await'"), instance member from a static context (CS0120), wrong argument type (CS1503) and missing arguments of generic, `params`
  and optional overloads and with named arguments (CS1501 / CS7036), conversions of `?:` and switch expressions (CS0029 / CS0266), and
  the simple nullable warnings where the nullable context is on: `null` to a non-nullable local, field, property, argument or return
  (CS8600, CS8625, CS8603) and fields and auto-properties left null by a class without constructors (CS8618). `#pragma warning
  disable`, `<NoWarn>` and `.editorconfig` severities are respected
- "Add argument name" context action: the parameter name before a positional argument and the ones after it

## 0.1.77

- Source generators without the language server: the generators of a project (`[GeneratedRegex]`, System.Text.Json, `[LoggerMessage]`,
  options validation, generator packages such as CommunityToolkit.Mvvm; not Razor) run in a helper of the plugin on save, after a build
  and on .NET | Code Analysis | Refresh Generated Files. Their files are under Dependencies → .NET x → Analyzers → generator, read-only
  with a banner, and what they declare resolves: completion, Go to Declaration and the built-in errors see it, and the errors no longer
  stay silent in projects with generators (a member missing from a partial type is reported once its generated part is known)
- Roslyn analyzers without the language server: the analyzers of the packages of a project, the CA rules and the IDE code style rules of
  the SDK, with the severities of `.editorconfig`, run in the background a second after a save (on the saved file) and on .NET | Code
  Analysis | Run Code Analysis (the whole project or solution, listed in the Build tool window). Their warnings are in the editor with
  the id first, suggestions (Info) as weak warnings, and Alt+Enter offers the code fixes of the analyzers ("Make static", "Rename to
  LoadAsync"...), applied as one undoable command. A warning of the last build that the analyzers report live is not shown twice
- Settings | .NET | Analyzers and Generators: run source generators, run analyzers on save, show suggestions, and when the helper stops
  without requests (10 minutes). The analyzers stand back while the language server is enabled, which runs them itself
- The helper (CodeAnalysisHelper) is built from source on first use with the newest .NET SDK of the machine, 8 or newer, against the Roslyn
  that SDK carries (no download); one per solution. On the playground (2 projects, 414 analyzers) it loads in ~4 s, analyzes a file in
  ~1 s the first time and in ~0.1–0.5 s after, applies a fix in ~0.3 s, and takes ~300 MB

## 0.1.76

- The C# language server (roslyn-language-server) is off by default: completion, navigation, Find Usages, rename, hierarchies, errors,
  quick documentation, context actions, Generate and Extract Method come from the plugin's own C# support. The server can still be
  turned on in Settings | .NET | Language Server; without it the plugin no longer asks for the .NET 10 SDK

## 0.1.75

- Generate (Alt+Insert) without the language server, with Rider's generators: Constructor (with base constructors), Read-only properties,
  Properties, Missing members (abstract members and interfaces, from the solution and from referenced assemblies), Overriding members,
  Partial members, Deconstructor, Equality members (`IEquatable<T>`, `HashCode.Combine`, equality operators), Formatting members
  (`ToString`) and the Dispose pattern. Members are chosen in a dialog grouped as in Rider; the code is formatted, gets the `using`
  directives it needs and goes where the caret is. Rows with nothing to offer are gray; the language server's rows of the same
  generators are no longer listed twice. Code | Implement Methods (Ctrl+I) and Override Methods (Ctrl+O) open the same dialogs in C#
- Extract Method (Ctrl+Alt+M, Refactor This) without the language server: statements or an expression; parameters from the locals it
  reads, the value it returns or `out` parameters for more, `async` when it awaits, `static` when it uses no instance member; the new
  name is edited in place. Refused with a hint when the selection returns, breaks out of a loop or jumps
- Introduce Field (Ctrl+Alt+F, Refactor This) without the language server: a `readonly` field initialized where it is declared, or a
  field assigned in the member when the expression reads locals

## 0.1.74

- C# errors without the language server ("Errors and warnings" = Built-in, the default): the plugin's own resolver reports the errors it is
  sure of with Roslyn's codes, texts and places, as you type and before any build — CS0103 / CS0246 / CS0234 (unknown name, type or
  namespace), CS1061 / CS0117 (no such member), CS1501 / CS7036 (wrong number of arguments), CS0029 / CS0266 (no implicit conversion) and
  CS0161 (not all code paths return a value). It stays silent when anything is unknown (an assembly not indexed yet, a generated file, a
  syntax error in the member), so a red mark is a real error
- Unused `using` directives are gray (CS8019, CS8933 for a repeat of a global using), with "Remove unused directives in file"
- "Import type" on an unknown type or name, and on an extension method that is not imported: the blue `System.Diagnostics.Stopwatch?
  Alt+Enter` hint as in Rider, a list of namespaces when there are several
- An error the plugin shows is no longer shown a second time by the language server or by the last build
- The assemblies of a project outside the solution are indexed once a file of it is open (completion and errors there too)

## 0.1.73

- References across the solution without the C# language server (Settings | .NET | Language Server → Source of Features →
  "Navigation and usages" and "Rename", Built-in by default): the plugin's own resolver finds them, in every project of the solution
- Find Usages (Alt+F7) and Show Usages (Ctrl+Alt+F7) of types and members: usages through the hierarchy of a member (a call through the
  interface counts for the implementation, as with the server), `nameof`, doc comment `cref`, `base(…)` and target-typed `new()` for
  constructors, members in property patterns; grouped by Read / Write / nameof / documentation as before. Usages of a type or member of a
  referenced assembly from its use in the code or from its metadata view
- The usages of a member or type under the caret are highlighted in the file (writes in the write color)
- Go to Implementation (Ctrl+Alt+B): the classes below a type or interface (every part of a partial one), the overrides and
  implementations of a member; Go to Super (Ctrl+U): the base types, the member a member overrides or implements (in an assembly — its
  metadata view)
- Type Hierarchy (Ctrl+H) with its Supertypes / Subtypes views and Call Hierarchy (Ctrl+Alt+H) with callers and callees
- Gutter icons as in Rider: "Overrides member", "Implements member", "Is overridden", "Has implementations", "Has subclasses"; a click
  lists them
- Rename (Shift+F6) of types and members across the solution: every declaration and usage (`nameof` and `cref` too), constructors and
  the finalizer with their type, the file named after the type; for a member of a hierarchy a dialog asks whether to rename the members it
  overrides or implements and the ones that override it ("Rename All" / "Only This"); a member or type of the same name is shown as a conflict
- With "Language server" chosen for these features and the server ready, the server answers all of the above, as before

## 0.1.72

- Quick documentation and parameter info, and the Alt+Enter context actions are now **Built-in** by default (Settings | .NET |
  Language Server → Source of Features): checked against the language server on the playground; switch back to "Language server" there
- Quick documentation: `<inheritdoc/>` shows the documentation of the base member, the interface member or the `cref` it names (of the
  solution and of the assemblies), with the member's own parts kept; `cref`s are links — a click shows the documentation of that symbol,
  F4 opens it (its declaration, or the metadata view of an assembly); Ctrl+Q on `var` shows the type it stands for (`T is int`);
  parameters of library methods show their nullability as Roslyn does (`string? value`); one popup instead of two pages when the server runs
- Parameter info: the constructor the arguments pick is marked (`new StringBuilder(16)`); on a named argument (`count: 2`) the parameter
  of that name is highlighted and the overloads without it are greyed
- Completion after a dot: no `System.Void`, no `Finalize` after `this.`, only the members of an enum after its name (as the server)
- Context actions: "Inline variable" also on `var` and on the type of the declaration; the server's "Replace conditional expression with
  statements" no longer stands beside the built-in "Convert '?:' to 'if' statement"

## 0.1.71

- Colors inside C# strings, as in Rider: the code in the holes of interpolated strings (`$"Total {x + 1:N2}"`) is colored as code
  (keywords, numbers, operators, nested strings), the braces of a hole as braces, alignment and format (`,5`, `:N2`) as a format item;
  raw strings with `$$"""` holes too
- Escape sequences are highlighted: `\t`, `\n`, `A`, `""` of verbatim strings, `{{` / `}}` of interpolated ones, in two alternating
  colors when they stand side by side; an invalid one (`\q`) is marked
- Format items of `string.Format`, `Console.WriteLine`, `StringBuilder.AppendFormat` (`{0}`, `{1,5:N2}`) get the format item color
- Settings | Editor | Color Scheme | C#: new group "String" with "String text", "Escape sequence" (Valid, Valid 2, Invalid) and "Format item"

## 0.1.70

- The settings of the plugin have a node of their own at the root of Settings: **.NET**, with Toolset and Build, NuGet, Coverage,
  Debugger and Language Server under it (they were under Tools). Editor | Code Style | C# and Color Scheme | C# stay where they were
- The main toolbar has Rider's **Build Solution** button right before the Run widget instead of the Debug / Release and target
  framework combo box: the hammer builds the solution, its arrow opens Build, Rebuild, Clean, NuGet Restore, Cancel Build and the
  choice of the configuration and the target framework
- **Refactor This** (Ctrl+Alt+Shift+T) in a C# file lists what can be done at the caret or on the selection, as in Rider: Rename,
  Introduce / Inline Variable, Move type to its file, the refactorings of the language server (Introduce local, Extract method,
  Extract base class...) — only what is available
- **Navigate To** (Ctrl+Shift+G, also in the Navigate menu): Declaration, Implementation, Base Symbols, Find Usages, Related Files,
  Type of Symbol, Related Tests, Show Usages, Type / Call Hierarchy, IL Code, Reveal in the file manager
- **Generate** (Alt+Insert) in a C# file shows the generators at once, in Rider's order: those of the language server (constructor,
  Equals and GetHashCode, overrides, interface members), Unit Test, Partial Part, Insert New GUID — no second popup
- The menu of the editor of a C# file has Rider's Find Usages Advanced..., Inspect (Call Hierarchy, Type Hierarchy, IL Code),
  Quick Definition and "Generate Code..."
- Fixed: Call / Type Hierarchy asked the language server while a menu was being shown and reported an IDE error; the server is now
  asked when the action is chosen. A build stopped with Cancel Build is shown as cancelled, not as failed
- The completion list no longer opens by itself after `{`, `(`, `,` and the other characters the language server triggers on with
  nothing typed (`GetStringAsync(...){` listed the parameters and fields of the class): as in Rider, it opens by itself after `.`,
  after `[` of an attribute and after a space where a type, a name or a keyword is expected; Ctrl+Space lists everything as before

## 0.1.69

- Debugging of .NET Framework 4.x programs on Windows with `dotnet-debugger` 0.2.0: Debug of a project of the old format starts the
  program its build has made (`TargetPath`), in its output folder as in Visual Studio; breakpoints, stepping, variables and evaluation
  work as for .NET
- A 32-bit program (AnyCPU with "Prefer 32-bit", the default of old project templates, or x86) is refused before the debugger starts,
  with what to change in the project: the debugger debugs 64-bit processes only. Attach to a 32-bit process is refused the same way
- Run | Attach to Process offers .NET Framework processes on Windows without a registry key (the key
  `dotnet.debugger.attach.netFramework` is gone): managed executables, from the first opening of the list, and hosts with the desktop
  CLR loaded
- Debug builds the project once: the program found by "Build .NET Project" reaches the debugger (every Debug used to build the project
  a second time)
- The editor no longer says that the packages of a project of the old format are not restored: such a project has no
  `project.assets.json`
- Typing `"` after `$` or `@` (`Console.WriteLine($"`) puts the closing quote, as for a plain string
- Debug no longer reports an IDE error "[Split debugger] RunContentDescriptor should not be used in split mode" on every start
  (IntelliJ 2026.1)

## 0.1.68

- The built-in formatter (Reformat Code with the formatter "Built-in") lays out initializers and argument lists as Rider does, where
  `dotnet format` leaves them as they are. A multi-line object, collection, array or anonymous-object initializer and a multi-line
  collection expression get the `{` / `[` and the `}` / `]` on lines of their own, the elements one indent in; the elements keep their
  lines (`1, 2,` stays together) unless one of them spans lines, then each goes to a line of its own. One-line ones get Rider's spaces:
  `new List<int> { 1, 2, 3 }`, `[1, 2, 3]`. `csharp_new_line_before_open_brace` without `object_collection_array_initializers` keeps
  the brace at the end of the line
- The lines of a multi-line argument list (calls, `new`, indexers, attributes, `: base(...)`) and of a parameter list go one indent
  right of the line of the call, as in Rider (not under the first argument); a `)` on a line of its own goes under that line, nested
  lists one indent further when the outer arguments are on lines of their own. Line breaks are never added or removed there

## 0.1.67

- A folder with a .NET solution or project opened in IntelliJ IDEA gets a module with the folder as its content root, as GoLand,
  PyCharm and WebStorm make one. IDEA 2026.1 opened such a folder without a module, and then its C# files were not indexed: the
  Built-in identifier colors, the plugin's Alt+Enter actions ("Make method async", the `using` conversions, "Convert to 'global using'",
  the context actions), the types of other files and Go to Class were missing. The Built-in colors and the Alt+Enter actions also
  work in a C# file the IDE does not index (a project outside the opened folder)
- The text of an inactive `#if` branch is gray (Settings | Editor | Color Scheme | C# → Preprocessor → Inactive branch), from the
  symbols of the project and the configuration chosen in the toolbar
- No completion list in or right after a number: `int x = 1` and Enter no longer writes `1_resized`
- The errors of the language server carry their code like the plugin's own (`CS0230: Type and identifier are both required…`)
- Ctrl + hover over a name the plugin's navigation resolves (a local, a parameter, a member) shows its line (`(local variable) int total`)
- The panel of gray text names the plugin, not an internal id ("Tab to complete io.github.dotnetsupport.declarations")
- Alt+Enter: the server's "Fix All: Make method async" (and the other "Fix All" rows of actions the plugin does itself) no longer
  stands above the plugin's own row

## 0.1.66

- Completion after a dot without the language server (Settings | Tools | .NET | Language Server → "Completion" = Built-in): `orders.`
  lists the members of the type of `orders` — of the solution and of the referenced assemblies, the inherited ones included, with the
  type arguments filled in (`Add(int item)` of a `List<int>`) — and the extension methods of the imported namespaces, without their
  `this` parameter; `Console.` lists the static members and nested types, `System.` the namespaces and types, `Color.` the enum members.
  Only what C# lets the place see: private members inside their type, protected ones inside a derived type (of a library base only
  through `this.`). The language server's items of the same names are not shown twice
- Quick documentation (Ctrl+Q, hover) and Parameter Info (Ctrl+P) on the plugin's own semantics, with "Documentation and parameter info"
  = Built-in (the language server stays the default until checked live): the first line as Rider writes it
  (`void Console.WriteLine(string value) (+ 17 overloads)`, `(parameter) int limit`, `decimal Order.Total { get; set; }`), the XML
  documentation of `///` comments and of the documentation files of assemblies (summary, parameters, returns, exceptions, remarks);
  every overload of a call as a row, the one that fits marked
- Extension methods of the solution declared on a keyword type (`this string text`) are found for colors and Go to Declaration too

## 0.1.65

- Errors of `using` without the language server ("Errors and warnings" = Built-in): CS1674 on a resource of `using` that is not
  `IDisposable` (`using (var n = 5)`), CS8410 on `await using` of something not `IAsyncDisposable`, and Roslyn's "Did you mean 'using'
  rather than 'await using'?" / the reverse when the other interface is there — with the texts and codes of the compiler, shown once.
  Nothing is reported where the type is not known through and through (an unknown type or base, a type parameter, a `DisposeAsync`
  method of the pattern)
- The completion list at `using (` and `using var x = ` leaves out the locals, parameters, fields and properties known not to be
  disposable (`IAsyncDisposable` for `await using`)

## 0.1.64

- Alt+Enter actions of the C# code without the language server (Settings | Tools | .NET | Language Server → "Context actions" =
  Built-in; without the server they work anyway): "Convert to '?:' expression" for an `if` that returns or assigns in both branches,
  "Convert '?:' to 'if' statement", "To expression body" / "To block body" for methods, local functions, constructors, operators,
  properties, indexers and accessors, "Use explicit type" (the type as the place writes it) / "Use 'var'", "Introduce variable" (the
  selection or the call at the caret, the name edited in both places at once) and "Inline variable". With Built-in the server's rows
  of the same actions are not shown twice

## 0.1.63

- Decompiled sources, as in Rider: Solution view → Dependencies → a package, an assembly of a framework (Frameworks →
  Microsoft.NETCore.App → System.Text.Json) or a referenced assembly → Decompile... lists its types; the chosen one opens as read-only C#
  in a tab `JsonSerializer.cs [System.Text.Json]` with the banner "Decompiled from System.Text.Json 10.0.0.0. Read-only", colors, folding,
  Structure and the XML documentation of the members. No `ilspycmd` or other global tool: the plugin's .NET helper decompiles with
  ICSharpCode.Decompiler (the engine of ILSpy). A reference assembly is decompiled from its implementation (the shared framework, `lib/` of
  the package), so the methods have bodies; a type forwarded elsewhere (`System.String` of `System.Runtime`) comes from where it lives.
  Decompiled types are kept on disk: a tab reopened with the project and Back in the navigation history find them again; a rebuilt or
  updated assembly is decompiled anew
- Built-in Go to Declaration (Ctrl+B) of a type or a member of a library opens its decompiled code once the type has been decompiled;
  the first time it opens the metadata view at once and decompiles the type in the background for the next time

## 0.1.62

- Go to Class (Ctrl+N) and Go to Symbol (Ctrl+Alt+Shift+N) find the types and members of the referenced assemblies — the framework,
  NuGet packages, assemblies by path — when "Include non-project items" is on (Search Everywhere: "All Places"), without the language
  server: `List<T> (System.Collections.Generic, System.Collections 10.0)`, one row for an assembly several projects refer to
- They open the metadata view of the type, as Rider does without a decompiler: a read-only C# file made of the index of the assembly —
  where the assembly is and its version, the type with its generic parameters, constraints, bases and attributes, every public and
  protected member as a signature without a body, the XML documentation as `///`, the nested types — with the caret on the member;
  the tab says which assembly it is and survives a restart of the IDE
- Built-in Go to Declaration (Ctrl+B, Ctrl+click; Settings | Tools | .NET | Language Server → "Navigation and usages" = Built-in) of a
  type or a member of an assembly opens its metadata view while the language server is not ready; with the server ready its decompiled
  source opens as before

## 0.1.61

- `using` on the plugin's own C# tree. Completion (Built-in): `using var` and `await using var` at the start of a statement (the latter
  makes the method `async`, as `await` does), locals, `var` and `new` in `using (`, namespaces of the solution and of the referenced
  assemblies after `using ` (level by level after a dot), types too after `using static ` and `using X = `, `global using` at the top of a
  file
- Postfix `.awaitusing` next to `.using`; both name the variable after the type made or the method called
  (`new StreamReader(path).using` → `using var reader = …`), `.awaitusing` makes the method `async`
- Alt+Enter: Convert to 'using' declaration / Convert to 'using' statement, Wrap in 'using' statement (a local made by `new`), Sort
  'using' directives (global first, `System` first), Convert to 'global using' (moves the directive to the project's `GlobalUsings.cs`,
  made when there is none). "Make method async" works on `await using` and `await foreach` too; with the Built-in source the server's
  row of the same action is no longer listed twice

## 0.1.60

- Navigation and usages, Completion and Colors of identifiers are built in by default (Settings | Tools | .NET | Language Server |
  Source of Features): a robot compared them with the language server on the playground — Go to Declaration went to the same places,
  completion lost nothing of the server's list, and every name the server colors got the same or a finer color
- Built-in Go to Declaration on `new Order()` goes to the constructor, as the language server does, not to the class
- Built-in completion offers the keywords it missed (`global`, `ref`, `stackalloc`, `static` in expressions; `dynamic`, `extern`,
  `scoped`, `void` in statements; `sealed` and `ref` at the start of a member) and no longer shows the language server's `override`
  members a second time next to its own; a named argument `amount:` stays next to the local `amount`
- Choosing the language server's `await`, `override` or `partial` item no longer fails (a stack overflow in the IDE log) and no longer
  leaves `awaitait` or `();` behind: the whole edit of the server is applied
- Built-in colors: switching «Colors of identifiers» no longer leaves the language server's colors under the built-in ones; calls of
  overloads with an argument of unknown type (`Console.WriteLine(x)`), `g.Key` of `group … into g` and methods on a LINQ query get
  their colors

## 0.1.59

- The assemblies the solution is compiled against are libraries of the IDE: Project view → External Libraries lists the framework
  packs (`Microsoft.NETCore.App.Ref 10.0.12`, `.NETFramework 4.8.1`), the NuGet packages with their versions and the assemblies
  referenced by path, each with its dlls; they are in the "All Places" scope and not in the project's. Updated after restore, a change of
  a project file or of the framework in the toolbar; only the dlls are added (binary, nothing reads their content), not the XML docs next
  to them

## 0.1.58

- Built-in Go to Declaration (Ctrl+B) and colors of identifiers now know the type of any expression, not only of a name: a call with
  inferred type arguments (`orders.First(o => ...).Total`, `items.Select(x => x).Last()`), `await`, an indexer, `?.` and `??`, `?:`,
  operators, tuples with element names, deconstruction, `foreach` over a dictionary, LINQ query expressions and the parameters of
  lambdas — the member after a dot on them goes to its declaration in the solution without the language server
- `var` takes the type of what it is initialized with, so members of such variables resolve as well

## 0.1.57

- Built-in colors of identifiers (Settings | Tools | .NET | Language Server → «Colors of identifiers» = Built-in) now know the
  referenced assemblies: `Console`, `List`, `Math.PI`, `WriteLine`, `numbers.Count`, extension methods like `Where` get the colors
  of their kinds without the language server, and the namespaces of `using static` / alias directives are colored
- Built-in Go to Declaration (Ctrl+B) goes to a member after a dot when the type of the value before it is known (`order.Total`,
  `Make().Total`, `this.Items`) and to the parameter of a named argument; members of assemblies are still left to the language server
- The plugin resolves C# names itself: namespaces, `using` / alias / `global using` (from other files and the project's implicit
  usings), types of the solution and of the referenced assemblies, inherited members, overloads by arguments, extension methods

## 0.1.56

- Errors and warnings and Rename are built in by default (Settings | Tools | .NET | Language Server | Source of Features): the syntax errors
  show at once while typing, with the compiler's codes, and the semantic ones still come from the language server; Shift+F6 renames
  locals, parameters, local functions, labels and type parameters without waiting for the server (members and types still go to it)
- Switching «Errors and warnings» no longer leaves the language server's syntax errors doubled or missing until the next edit
- Rename: the check of conflicts and the edit itself run after the inline rename ends, not inside it (an error of the IDE about a
  write-unsafe context)

## 0.1.55

- Completion of C# files can come from the plugin itself: Settings | Tools | .NET | Language Server | Source of Features |
  «Completion» = Built-in lists, at once and before the language server has loaded the solution, the keywords the place allows
  (`break` / `continue` only in a loop, `else` after an `if`, `catch` / `finally` after a `try`, `yield` in an iterator, the
  modifiers not typed yet at the start of a member), the locals, parameters, members and types in scope — locals first, then
  parameters, members, types and keywords, what fits the expected type higher — `override` of the base members (the whole
  member is written: `throw new NotImplementedException();` for an abstract one, a `base` call for a virtual one), `partial`
  methods and names for a new variable after its type (`StringBuilder ` → `builder`, `stringBuilder`). The server's items
  join the list once it is ready, without doubles. Language server stays the default until the scenarios are checked
- In a method that returns `Task<T>` / `Task` / `ValueTask` and is not `async`, `return ` gets the gray text
  `Task.FromResult();` / `Task.CompletedTask;` (Tab) and the same first item of completion
- Choosing `await` in a method that is not `async` makes it `async` (`void` becomes `Task`, `int` becomes `Task<int>`;
  event handlers stay `async void`); Alt+Enter on an `await` offers «Make method async» without the language server

## 0.1.54

- Syntax errors of C# files can come from the plugin itself: Settings | Tools | .NET | Language Server | Source of Features |
  «Errors and warnings» = Built-in shows the errors of the parser, of literals and of preprocessor directives with the compiler's
  codes, messages and places (`CS1002: ; expected` after the end of the line, not on the next one), at once while typing and before
  the language server has loaded the solution. The server still reports the semantic errors, and gives way only on the syntax errors
  the plugin shows itself, so nothing is shown twice. Language server stays the default until the scenarios are checked
- Without the language server (turned off) the syntax errors are shown by the plugin

## 0.1.53

- Rename (Shift+F6) on the built-in C# syntax tree (Settings | Tools | .NET | Language Server → «Rename» — Built-in; the default stays
  Language server for now): locals, parameters of lambdas, anonymous methods and local functions, parameters of methods, constructors,
  indexers and primary constructors, local functions, labels, query range variables and type parameters are renamed in place, without
  the server: every use in the file follows while typing, named arguments of the calls and `<param>` / `<typeparam>` tags of the doc
  comment too, and one Ctrl+Z brings everything back. A reserved keyword gets `@` (`@class`); a name that would change what another name
  means (a field it would hide, a lambda parameter that would capture a use, a second declaration in the same scope) shows the
  «Problems Detected» dialog. Members and types, a parameter used as a named argument in another file and the type parameters of a
  `partial` declaration still go to the language server; before it is ready a hint says why
- Navigation, the highlighting of usages, colors and rename on the built-in tree now share one resolver of names. Go to Declaration
  gains what only the colors knew: members of base classes of the solution, `base.X`, `Type.X`, members set by an object initializer,
  members of `using static` types. Scopes follow C# more closely everywhere: a local is visible in its whole block, variables of
  `while` / `do` / `lock` conditions and embedded statements stay there, a query continuation (`into g`) hides the variables before it.
  The positional parameters of a record are properties: their usages are no longer highlighted as if they were local

## 0.1.52

- The index of assemblies (format 2) keeps what a library shows to its users, not only what completion of unimported members needed:
  every public and protected type with its generic parameters and constraints, base type, interfaces, nested types and attributes,
  and every public and protected member — instance and static methods, constructors, operators, properties and indexers, events,
  fields, constants with their values — with signatures (nullable annotations, tuple element names, `ref` / `out` / `in` /
  `params`, default values). Extension methods are found by what they extend, and the XML documentation of a package or of the SDK
  is indexed as well (a separate compressed file, read only when asked for). This is the groundwork for the plugin's own C#
  semantics; nothing changes in the editor yet
- Indexing runs on half of the cores: the reference pack of .NET 10 with its documentation takes 0.8 s once per machine
- What a project is compiled against now follows the framework chosen in the toolbar, and includes projects of the old format
  (`Reference` with `HintPath`, the .NET Framework reference assemblies of `TargetFrameworkVersion`), the reference assemblies of
  .NET Framework for SDK projects (the `Microsoft.NETFramework.ReferenceAssemblies` package or the ones installed on the machine),
  and reference packs restore has downloaded for a framework the SDK has no pack of. Completion of unimported members sees them
- Members marked `[EditorBrowsable(Never)]` are still not offered by completion of unimported members

## 0.1.51

- Colors of identifiers in the palette of Rider: Settings | Editor | Color Scheme | C# now has Rider's groups and names — class,
  static class, record, struct, record struct, interface, enum, delegate, type parameter, attribute, namespace; method declaration and
  call, static and extension methods, local function; field, static field, constant (bold, also enum members), property, static
  property, event; local variable, mutable local variable (underlined: written again after its declaration), parameter, primary
  constructor parameter, label. Every new color falls back to the three colors of before, so custom schemes keep working
- The language server's colors use the new palette too (`static`, reassigned locals, locals and parameters, namespaces, labels)
- Colors of identifiers on the built-in C# syntax tree (Settings | Tools | .NET | Language Server → «Colors of identifiers» —
  Built-in; the default stays Language server for now): declarations by their kind and modifiers, locals, parameters, local functions,
  type parameters and labels with their uses, members of the type and its partial parts and base classes in the solution, `this.X`,
  `Type.X`, `using static`, object initializers, types of the solution by kind — read from the index, without parsing other files and
  without the server. What only the server can resolve (library types and their members) stays uncolored in this mode; switching the
  source recolors open editors at once

## 0.1.50

- Go to Declaration (Ctrl+B, Ctrl+click), Ctrl+hover and the highlighting of usages under the caret on the built-in C# syntax tree
  (Settings | Tools | .NET | Language Server → «Navigation and usages» — Built-in; the default stays Language server for now): locals,
  `out var` and pattern variables, deconstruction, `foreach` / `for` / `using` / `catch` variables, parameters of methods, constructors,
  primary constructors, lambdas and local functions, local functions, `goto` labels, query range variables and type parameters, by the
  scopes of C# (a lambda's parameter hides a local, a local hides a field) — without waiting for the language server
- Members of the enclosing type and of its other `partial` parts by name (overloads are offered as a list) and the types of the
  solution by name, among the namespaces the file is in and imports with `using`
- What the tree cannot tell (a member after a dot, base members, types of packages and the framework) still goes to the language
  server, and Go to Super (Ctrl+U) works whichever source is chosen: switching to Built-in loses no navigation
- Highlighting of a local tells reads from writes (a declaration, `=`, `++`, `out` and `ref` are writes) and stays within its method

## 0.1.49

- Reformat Code on the built-in C# syntax tree (Settings | Tools | .NET | Language Server → «Formatting», Built-in by default): what `dotnet format whitespace` does, inside the IDE and without a process — indents, braces on lines of
  their own, spaces around operators and after keywords, `case` labels, the blank lines kept, the `indent_*` and `csharp_*` options of
  `.editorconfig`; literals, comments and inactive `#if` branches are never touched
- Reformat Selection, Code | Auto-Indent Lines and the indent of a paste work with it; with CSharpier chosen or found by «Auto»
  CSharpier still formats, «None» still turns formatting off
- Settings | Tools | .NET | Toolset and Build → «Formatter»: a new «Built-in» choice next to «dotnet format (on save)». The built-in
  formatter works as you go (Reformat Code, a selection, Auto-Indent Lines, a paste); `dotnet format` formats whole files only, so it
  is meant for Reformat Code and «Reformat code» on save. «Auto» picks between the two by the «Formatting» switch of the Language
  Server page; a chosen formatter no longer depends on that switch
- «None» no longer lets Reformat Code or Reformat Selection touch C# files

## 0.1.48

- Typing assistance on the built-in C# syntax tree (Settings | Tools | .NET | Language Server → «Typing assistance» — Built-in, the
  default, or Language server for the previous token rules):
  - Extend Selection (Ctrl+W) goes through the syntax: `name` → `name.Trim()` → the argument list → the call → the expression → the
    statement → the block → the member with its attributes and doc comment → the type; the text of a string without its quotes, the hole
    of an interpolated string
  - Complete Statement (Ctrl+Shift+Enter) adds the `)`, `]` and `;` a statement lacks (`Foo(a, b` → `Foo(a, b);`), gives `if`, `foreach`,
    `while`, a method or a class without a body a block with the caret in it, and no longer splits a call written over two lines
  - The gray `;` is offered at the end of a statement written over several lines, and not after a line that only looks finished
- The gray text of lambdas and arguments of the language server follows the parameter info, not the typing assistance switch
- Completion of members that are not imported no longer offers static methods where only a type may stand: in `Task<…>` and other
  type arguments, `typeof(…)`, after `as`, in a base list and a `where` constraint (`Task<str` offered `Conversion.Str`)

## 0.1.47

- Go to Class and Go to Symbol on the built-in C# syntax tree use stub indexes: the rows come from the index, files are not parsed to
  show them
- C# files are parsed once while the IDE indexes them (the old declaration index serves only the "Language server" tree)
- Indexing follows each file's `#if` symbols and language version; such files are reindexed and parsed again when the target
  framework or the project configuration changes, so Go to Class leads to the class of the active `#if` branch
- New indexes of extension methods and of test attributes (`[Fact]`, `[Test]`) for the coming features

## 0.1.46

- Find Usages groups usages by kind on the plugin's own C# tree: Settings | Tools | .NET | Language Server → Source of Features →
  "Kinds of usages" — Built-in (the default), or Language server (the previous token heuristics). The tree tells more exactly: a
  deconstruction `(a, b) = …` writes `a` and `b`; a member given a nested initializer (`Lines = { 1, 2 }`) is read, not written; the
  type of `out Order o` is a declaration type, not a write; a type in a `switch` pattern is a type check; query variables, lambda
  parameters and members of anonymous types are declarations; the text of an interpolated string is a string. It works while the
  IDE indexes

## 0.1.45

- C# files are now parsed by the plugin's own parser, a port of Roslyn's (checked against the compiler: the same trees on the
  sources of Roslyn, dotnet/runtime and aspnetcore). Structure view, folding, breadcrumbs, Go to Class / Symbol, the IL Viewer, Go to
  Base, run markers of tests and the other features that read declarations take them from its tree, without waiting for the language
  server. Fixed on the way: a method with two headers under `#if` / `#else` no longer swallows the rest of its class, a `record` after
  a top-level `using (…) { … }` is found, `int a, b;` shows both fields, `[assembly: …]` is not part of the namespace below it, the
  primary constructor of a generic record and the attributes of an enum member are shown
- `#if` regions follow the project: the symbols and the C# version of a file come from its project and the configuration and target
  framework chosen in the toolbar; switching the target framework updates the Structure view
- Settings | Tools | .NET | Language Server → Source of Features: "Structure, folding and breadcrumbs" — Built-in (the default) or
  Language server (the previous heuristics)

## 0.1.44

- Identifier colours while the solution loads: a C# file restored from the last session is coloured by the plugin's heuristics as soon
  as it is shown, also when another tab (README.md of the folder) was opened over it; the heuristics step aside only once the server's
  colours of that file are on screen, not when the server becomes ready (a file had no colours for 100–300 ms then)
- The semantic tokens kept from the last session colour an opened file before the server is ready (about 2 s after opening the
  playground instead of 4.5–5 s): they were stored but never shown
- Identifier colours no longer drop to none and come back when the server refreshes its tokens (seen 1–4 times while a solution
  loaded): the old colours stay until the new ones replace them
- No `AlreadyDisposedException` in idea.log after a project with a running server is closed
- Go to Symbol / Go to Class show a C# member as Java does: `Area()` with its parameters, the type it is in in grey (`BaseShape`,
  `Outer.Inner`; for a type its namespace and outer types) and the file on the right — the same rows before the server is ready and
  after (an interface method and its three overrides were four identical rows "Area  GoToBase.cs"). The Structure view still shows the
  type after the name
- Completion: what matches the typed prefix in its case comes first: `pub` gives `public` above `PublicKey`, `public s` gives
  `sbyte`, `sealed`, `short`, `static`, `string`, `struct` above `String` and `SByte`, `str` (also in TYPE of the `prop` template)
  gives `string` first. Types of namespaces that are not imported (the server's and the plugin's) rank below keywords
- Typing the name of a property (`public RankedOrder Order`) no longer pops up the server's name suggestions, which hid the grey
  ` { get; set; }` and took Tab; Ctrl+Space still lists them, and a lower-case name (a field) still gets them
- Developer tools: `baseline.py` puts the measured file back in front and chooses the solution of a folder with several;
  `tools/roslyn-lsp/capture_keywords.py` records what the server answers where keywords and types compete

## 0.1.43

- Groundwork for the plugin's own C# code model (no change in behaviour yet): for every C# file the plugin now knows what the compiler
  of its project gets in the configuration and target framework chosen in the toolbar — the conditional compilation symbols (`DEBUG`,
  `TRACE`, `NET10_0`, `NETFRAMEWORK`, `NET48_OR_GREATER`... as MSBuild computes them), the C# language version (explicit, or the
  default of the SDK for the target framework), nullable context, global usings, the root namespace and the source files. Projects of
  the old format (.NET Framework without the SDK) are read too
- Structure view, breadcrumbs, folding, Go to Class / Symbol, Go to Base / Implementation, the IL Viewer, inline values, test markers and
  the other features that read the declarations of a C# file now get them through one model (`CSharpSyntaxModel`), so that the plugin's
  own parser can replace the heuristics later; the behaviour is pinned by snapshot tests. No visible change intended
- Developer tools: `tools/ui-robot/baseline.py` measures the editor (first highlighting, server ready, memory of the IDE and of the
  server, completion latency) on `debug-playground` and on a sample of ASP.NET Core; the numbers are in `CSHARP_PSI_MIGRATION.md`

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
