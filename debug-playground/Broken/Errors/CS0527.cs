// CS0527: Type 'T' in interface list is not an interface. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0527;

public class Entity { }
public interface IEntity { }
public interface INamed { string Name { get; } }
public struct Id { }

public struct Order : IEntity, Entity { } // ERROR CS0527
public struct Line : IEntity, IComparable<Line>
{
    public int CompareTo(Line other) => 0;
}
public interface IRepository : Entity { } // ERROR CS0527
public interface IReadRepository : IEntity, INamed { }
public record struct Key(int Value) : Exception; // ERROR CS0527
public record struct Code(string Value) : IEntity;
public struct Wrapper : Id { } // ERROR CS0527
public class Product : Entity, IEntity { }
