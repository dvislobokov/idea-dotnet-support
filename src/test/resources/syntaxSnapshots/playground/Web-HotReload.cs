// Hot Reload status of `dotnet watch`: run Web with a ".NET Project" configuration whose Command is "dotnet watch" (Run, not Debug),
// open /hot-reload, then edit the lines under the markers below and save (Ctrl+S). Undo every edit (Ctrl+Z) and save again afterwards.
// The state is shown in the row of the configuration in the Services tool window ("Hot Reload: ...") and the state lines of dotnet watch
// are colored in the run console.
namespace Playground.Web;

static class HotReloadScenario
{
    public static void MapHotReload(this WebApplication app) => app.MapGet("/hot-reload", () => Message() + " " + HotReloadCounter.Next());

    // TYPE:hot-reload-start — just start the configuration. EXPECT: Services row "Hot Reload: Building", then "Hot Reload: Watching for changes";
    //   in the console "dotnet watch ⌚ Waiting for changes" (SDK 10) or "Build succeeded: ...Web.csproj" (SDK 9) in the color of Log console info.
    // TYPE:hot-reload-apply — change "v1" to "v2" below and save. EXPECT: console line "dotnet watch 🔥 C# and Razor changes applied in N ms."
    //   (SDK 9: "Hot reload succeeded.") colored as info; Services row "Hot Reload: Changes applied"; refreshing /hot-reload shows v2 without a restart.
    // TYPE:hot-reload-error — replace "v1" below with undefinedThing (no quotes) and save. EXPECT: "Unable to apply changes due to compilation errors."
    //   (SDK 9: "Build failed: ...") in red, Services row "Hot Reload: Build failed" in red; the diagnostic line itself is not treated as a state.
    //   Undo and save: "Changes applied" again (SDK 9: rebuild, then "Watching for changes").
    static string Message() => "v1";
}

// TYPE:hot-reload-rude — delete the word "sealed" below and save: changing the modifiers of a class is a rude edit (ENC0004).
//   EXPECT: "Restart is needed to apply the changes." in orange, Services row "Hot Reload: Restart needed  Restart" with Restart as a link;
//   the line "❔ Do you want to restart your app? Yes (y) / ..." is a link too (typing y in the console does nothing: dotnet watch ignores a piped stdin).
//   Clicking either link, or "Restart dotnet watch" in the toolbar of the run console, reruns the configuration in the same tab; the counter starts from 1.
//   NOT expected: a second run tab, or the status staying after the process stopped (the row shows nothing for a stopped configuration).
sealed class HotReloadCounter
{
    static int _count;

    public static int Next() => Interlocked.Increment(ref _count);
}
