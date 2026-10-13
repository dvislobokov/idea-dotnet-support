using System;

namespace Playground.Editor;

// Completion in the header of a type (0.1.178): the base list, `where`, and the live templates by their scope, as in Rider.
// "Completion" = Built-in. Type on the marked spot, compare with EXPECT, Ctrl+Z.

public interface IShapeSample { }

// TYPE:base-list-library — put the caret after `: ` below and type `IDisp`, then `Excep`.
// EXPECT: IDisposable / Exception, ArgumentException... of the assemblies are in the list; `while`, `for`, `prop` are NOT (no templates in a header).
// In a controller: `: Contr` gives ControllerBase / Controller (with `using Microsoft.AspNetCore.Mvc;` or as a not-imported row).
public class BaseListSample : IShapeSample
{
}

// TYPE:base-list-after-first — type `, I` after `Exception` below.
// EXPECT: only interfaces (IDisposable, IShapeSample...); no Exception, no other class (the base class goes first).
public class FailureSample : Exception
{
}

// TYPE:base-list-struct — type ` : ` after `PointSample` and Ctrl+Space.
// EXPECT: interfaces only; no Exception, String, Int32, Console.
public struct PointSample
{
}

// TYPE:where-after-base — type ` wh` after `IShapeSample` below.
// EXPECT: `where`; on a non-generic class (BaseListSample) nothing.
public class BoxSample<TItem, TKey> : IShapeSample
    where TItem : class
{
    // TYPE:where-type-parameter — on a new line above `{` of BoxSample type `where ` and Ctrl+Space.
    // EXPECT: TKey only (TItem has its clause already).
}

public class TemplateScopesSample
{
    // TYPE:template-scopes — type `wh` on the empty line inside Run, then `pr` on the empty line under this comment.
    // EXPECT: in Run `while`, `if` templates, no `prop`; here `prop`, `ctor`, `class`, no `while`.

    public void Run()
    {

    }
}
