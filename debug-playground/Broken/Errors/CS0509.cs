// CS0509: 'D': cannot derive from sealed type 'B'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0509;

public sealed class Settings { }
public class Options { }
public struct Size { }
public interface IOptions { }
public enum Mode { On, Off }
public abstract class Component { }

public class AppSettings : Settings { } // ERROR CS0509
public class AppOptions : Options, IOptions { }
public class Text : string { } // ERROR CS0509
public class BigSize : Size { } // ERROR CS0509
public class Fancy : Mode { } // ERROR CS0509
public class Button : Component, IOptions { }
public class Failure : Exception { }
public class Generic<T> : Settings { } // ERROR CS0509
public sealed class Final : Options { }
public class Collection : List<int> { }

public sealed record Money(decimal Amount);
public record Entity(int Id);
public class Wallet : Money { public Wallet() : base(1) { } } // ERROR CS0509
public record Price : Money { public Price() : base(1) { } } // ERROR CS0509
public record Item : Entity { public Item() : base(1) { } }
