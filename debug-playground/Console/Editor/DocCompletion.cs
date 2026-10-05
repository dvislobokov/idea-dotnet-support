// TYPE:directive-hash — on an empty line at the very top (above `using`) type `#`. EXPECT: the list opens by itself: `#if`, `#region`, `#define`,
// `#elif`, `#else`, `#endif`, `#endregion`, `#error`, `#line`, `#nullable`, `#pragma`, `#undef`, `#warning`; `if` + Enter → `#if ` and the list of
// symbols opens: `DEBUG` (bold, "defined"), `TRACE`, `NET`, `NET9_0`, `NET9_0_OR_GREATER`…, `RELEASE`, `true`, `false`. Undo with Ctrl+Z.

// TYPE:directive-arguments — type `#nullable ` → `enable`, `disable`, `restore`, and after `#nullable enable ` → `annotations`, `warnings`;
// `#pragma warning disable ` → `CS0168`… with titles (codes the solution already names first). Undo with Ctrl+Z.

using System;
using System.Collections.Generic;

namespace Playground.Editor;

/// <summary>
/// Live check of completion in XML documentation comments and preprocessor directives (0.1.90). Server off (Settings | .NET | Language
/// Server). Type where a marker says and undo with Ctrl+Z. The file compiles as it is.
/// </summary>
public sealed class DocCompletion<TItem>
{
    private readonly List<TItem> _items = new();

    // TYPE:doc-tags — on the empty `///` line of `Find` type `<`. EXPECT: the list opens by itself: `summary`, `param`, `typeparam`, `returns`,
    // `remarks`, `exception`, `inheritdoc`, `see`, `seealso`, `paramref`, `typeparamref`, `value`, `example`, `code`, `c`, `para`, `list`…
    // `ret` + Enter → `<returns>|</returns>`; `par` + Enter on `param` → `<param name="|"></param>` and the list of parameters opens: `name`,
    // `limit` (`id` is documented already, so it is not there); Enter → the caret after `">`.
    // TYPE:doc-names — type `<typeparam name="` → `TKey` only; `<paramref name="` → `id`, `name`, `limit`.
    // TYPE:doc-cref — type `<see cref="` → `Find`, `Count`, `_items`, `DocCompletion`, types of the solution, `string`…; `<exception cref="` →
    // `ArgumentNullException` first. `</` after an open `<remarks>` → `remarks`.

    /// <summary>Finds an item.</summary>
    /// <param name="id">The id.</param>
    ///
    public TItem? Find<TKey>(int id, string? name = null, int limit = 10) where TKey : notnull => id < _items.Count && limit > 0 && name != "" ? _items[id] : default;

    /// <summary>How many there are.</summary>
    public int Count => _items.Count;

#if DEBUG
    // TYPE:directive-elif — on the empty line below type `#elif ` → the same symbols as after `#if `; `#end` → `endif`, `endregion`.

#endif
}
