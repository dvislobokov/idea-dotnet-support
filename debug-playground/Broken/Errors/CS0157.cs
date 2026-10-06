// CS0157: Control cannot leave the body of a finally clause. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0157;

public class Finally
{
    private int _open;

    public int Close()
    {
        try
        {
            _open--;
        }
        finally
        {
            if (_open < 0) return 0; // ERROR CS0157
        }
        return _open;
    }

    public void Drain(Queue<int> queue)
    {
        while (queue.Count > 0)
        {
            try { queue.Dequeue(); }
            finally
            {
                if (queue.Count == 1) break; // ERROR CS0157
                if (queue.Count == 2) continue; // ERROR CS0157
            }
        }
    }

    public void Retry()
    {
    again:
        try { _open++; }
        finally
        {
            if (_open < 3) goto again; // ERROR CS0157
        }
    }

    // the legal look-alikes: jumps that stay inside the finally, a return in a lambda, a throw
    public void Ok(List<int> items)
    {
        try { _open++; }
        finally
        {
            foreach (var item in items)
            {
                if (item < 0) break;
                if (item == 0) continue;
            }
            Func<int> count = () => { return items.Count; };
            if (count() > 100) goto log;
            _open--;
        log:
            Console.WriteLine(_open);
            if (_open < -1) throw new InvalidOperationException();
        }
    }
}
