// The «Analysis» scopes of Settings | .NET | Language Server without the server: «Compiler diagnostics for» and «Analyzer diagnostics for»
// (openFiles / fullSolution / none). With fullSolution the errors and warnings of the whole solution are in the Problems tool window
// (View | Tool Windows | Problems, tab Project Errors), as with the server. «Errors and warnings» = Built-in (the default). The file
// compiles: one warning on purpose (CS0219), so a closed file has a row to show.
namespace Playground.Editor;

public static class SolutionProblems
{
    // TYPE:problems-closed-file (permanent) — «Compiler diagnostics for» = fullSolution, this file NOT open. EXPECT: Project Errors lists
    // under SolutionProblems.cs the warning «CS0219: The variable 'unusedForProblemsTab' is assigned but its value is never used» (a yellow
    // icon, the line of the variable); double-click opens the line. With openFiles the row is there only while the file is open and goes
    // when its tab is closed; with none the tab has no C# rows and the editor shows no CSxxxx at all (the Current File tab is empty too),
    // the errors of the last build still show. Broken (not in the solution) is never listed, as in Rider.
    public static int Permanent()
    {
        int unusedForProblemsTab = 1;
        return 2;
    }

    // TYPE:problems-typing — fullSolution; type `int broken = ;` on the empty line below. EXPECT: the editor marks it at once; within ~1 s a
    // red row «CS1525: Invalid expression term ';'» appears under SolutionProblems.cs in Project Errors, once (not twice); Ctrl+Z — the row
    // goes within ~1 s. Typing stays smooth meanwhile; the counter of the tab's title follows.
    public static void Typing()
    {

    }

    // TYPE:problems-analyzers — «Analyzer diagnostics for» = fullSolution, Build Solution (or save any .cs file of Console). EXPECT: the
    // warnings of the analyzers of Console (Editor/Analyzers.cs: its .editorconfig makes three rules warnings) are rows of Project Errors
    // with their IDs (CA…/IDE…) next to the compiler's; the suggestions (Info) are not. With openFiles the analyzer rows are gone, the
    // editor still underlines them in the open file; with none the editor shows none of them either, .NET → Code Analysis → Run Code
    // Analysis still lists them in the Build window.
    public static void Analyzers() { }
}
