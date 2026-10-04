using System.Collections.Generic;

namespace Playground.Editor;

/// <summary>
/// Anchors of the measurement of the editor (<c>tools/ui-robot/baseline.py</c>, CSHARP_PSI_MIGRATION.md, step 0 «Исходные замеры»):
/// the script finds the markers below and types on the empty line under each, then undoes. The same anchors serve the measurement of
/// the native PSI later, so keep the markers and the empty lines. By hand: the same typing, the popup has to show what EXPECT says.
/// </summary>
public static class MeasurementsScenario
{
    public static int Members(string text, List<int> numbers)
    {
        // TYPE:measure-member — type `text.` on the empty line below.
        // EXPECT: the completion popup opens by itself with the members of string (Length, Substring, ...).

        // TYPE:measure-prefix — type `Consol` on the empty line below.
        // EXPECT: the completion popup opens by itself with Console in it.

        // TYPE:measure-edit — the script types statements here for a while (the editing session before the second memory snapshot).

        return text.Length + numbers.Count;
    }
}
