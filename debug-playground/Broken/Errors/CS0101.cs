// CS0101: The namespace 'N' already contains a definition for 'T'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0101
{
    public class Invoice { }
    public class Invoice { } // ERROR CS0101

    public struct Money { }
    public record Money(decimal Amount); // ERROR CS0101

    public interface IRepository { }
    public enum IRepository { None } // ERROR CS0101

    public delegate void Notify();
    public class Notify { } // ERROR CS0101

    public class Box<T> { }
    public class Box<T> { } // ERROR CS0101
    public class Box { }
    public class Box<TKey, TValue> { }

    public partial class Report { }
    public partial class Report { }

    public class Outer
    {
        public class Invoice { }
    }

    namespace Inner
    {
        public class Invoice { }
        public class Ledger { }
    }

    namespace Inner
    {
        public class Ledger { } // ERROR CS0101
    }
}
