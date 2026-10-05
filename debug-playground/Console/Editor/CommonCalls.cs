using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace Playground.Editor;

/// <summary>
/// Live check of the common calls of a task method (0.1.55, ROADMAP «Подсказки для частых вызовов»). Completion items need
/// Settings | Tools | .NET | Language Server → Source of Features → «Completion» = Built-in (the default is still «Language server»);
/// the gray text of `return ` and the intention «Make method async» work with any source. Type on the empty line under a marker
/// comment, check EXPECT, then undo (Ctrl+Z) so the file keeps compiling.
/// </summary>
public class CommonCalls
{
    // TYPE:complete-task-from-result — on the empty line in Count type `return ` (with the space). EXPECT: gray `Task.FromResult();`
    // right after the space; Tab takes it and puts the caret between the parentheses. Instead of Tab, Ctrl+Space: `Task.FromResult` is the
    // first item; Enter gives `return Task.FromResult(|);`. NOT: `Task.FromResult` in CountAsync (it is async: there `return 1;`)
    public Task<int> Count(List<int> items)
    {

        return Task.FromResult(items.Count);
    }

    public async Task<int> CountAsync(List<int> items)
    {
        await Task.Yield();

        return items.Count;
    }

    // TYPE:complete-task-completed — on the empty line in Save type `return `. EXPECT: gray `Task.CompletedTask;`; Ctrl+Space puts
    // `Task.CompletedTask` first. In Load (ValueTask<string>) the first item is `ValueTask.FromResult`, in Flush (ValueTask) it is
    // `ValueTask.CompletedTask` with `default` right under it
    public Task Save(string name)
    {

        return Task.CompletedTask;
    }

    public ValueTask<string> Load()
    {

        return ValueTask.FromResult("");
    }

    public ValueTask Flush()
    {

        return default;
    }

    // TYPE:complete-await-async — on the empty line in Total type `aw` and choose `await` (Built-in completion). EXPECT: `await ` is
    // inserted and the header becomes `public async Task<int> Total()`; Ctrl+Z twice gets the file back. In Fire (void) the header becomes
    // `async Task Fire()`; in OnClick (an event handler) it stays `async void`. NOT in Name (a property getter): no `await` item at all
    public int Total()
    {

        return 1;
    }

    public void Fire()
    {

    }

    public void OnClick(object sender, EventArgs e)
    {

    }

    public string Name
    {
        get
        {

            return "";
        }
    }

    // TYPE:complete-make-async — on the empty line in Delayed type `await Task.Delay(10);`, put the caret on `await`, Alt+Enter.
    // EXPECT: «Make method async» first; it gives `public async Task<int> Delayed()`. With «Completion» = Language server and the server
    // loaded the server's own fix «Make method async» is offered instead (one entry, not two)
    public int Delayed()
    {

        return 2;
    }
}
