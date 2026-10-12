// CS0264: Partial declarations of 'T<A>' must have the same type parameter names in the same order. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
// Roslyn reports it once per type, on the first part (the first in the compilation), whichever part differs.
namespace DebugPlayground.Broken.Errors.CS0264;

public partial class Repository<TEntity> { } // ERROR CS0264
public partial class Repository<TItem> { }

public partial class Map<TKey, TValue> { } // ERROR CS0264
public partial class Map<TKey, TResult> { }
public partial class Map<TKey, TResult> { }

public partial class Pair<TFirst, TSecond> { } // ERROR CS0264
public partial class Pair<TSecond, TFirst> { }

public partial struct Range<T> { }
public partial struct Range<T> { }

public partial class Result<T> { }
public partial class Result<T, TError> { }
public partial class Result<T, TError> { }

public partial interface IHandler<TMessage> { } // ERROR CS0264
public partial interface IHandler<TCommand> { }

public class Outer
{
    public partial class Inner<T> { } // ERROR CS0264
    public partial class Inner<U> { }
    public partial class Inner<T, U> { }
}
