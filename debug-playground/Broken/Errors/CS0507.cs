// CS0507: 'D.M()': cannot change access modifiers when overriding 'public' inherited member 'B.M()'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0507;

public abstract class Handler
{
    public abstract void Handle(string message);
    protected virtual void OnError(Exception error) { }
    public virtual string Name => "handler";
    protected virtual int Priority => 0;
    internal virtual void Trace() { }
}

public class LogHandler : Handler
{
    protected override void Handle(string message) { } // ERROR CS0507
    public override void OnError(Exception error) { } // ERROR CS0507
    internal override string Name => "log"; // ERROR CS0507
    protected override int Priority => 1;
    internal override void Trace() { }
}

public class QuietHandler : Handler
{
    public override void Handle(string message) { }
    protected override void OnError(Exception error) { }
    public override string Name => "quiet";
}

public class Plain
{
    override public string ToString() => "plain";
}

public class Hidden
{
    protected override string ToString() => "hidden"; // ERROR CS0507
    public override bool Equals(object? obj) => ReferenceEquals(this, obj);
    public override int GetHashCode() => 0;
}

public class QueueScheduler : TaskScheduler
{
    public override void QueueTask(Task task) { } // ERROR CS0507
    protected override bool TryDequeue(Task task) => false;
    public override IEnumerable<Task>? GetScheduledTasks() => null; // ERROR CS0507
    protected override bool TryExecuteTaskInline(Task task, bool previous) => false;
}

public class InlineScheduler : TaskScheduler
{
    protected override void QueueTask(Task task) { }
    protected internal override bool TryDequeue(Task task) => false; // ERROR CS0507
    protected override IEnumerable<Task>? GetScheduledTasks() => null;
    protected override bool TryExecuteTaskInline(Task task, bool previous) => false;
}
