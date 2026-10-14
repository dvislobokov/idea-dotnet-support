// docs: -; codes: CS0019 CS0266 CS1503 (0.1.185, SberEmp.MSR.TSS of a live project: enum member initializers, a conversion operator of a base class, a constant operand of uint &)
using System.Collections.Generic;

public enum Constants : uint
{
    Bits = 256,
    Bytes = ((Bits + 7) / 8),
    Data = 1024,
    Algs = (Data / 0x6),
    Doubled = Bits * 2,
}

public enum Other : uint { First = 24 }

public enum Handles : uint
{
    Pcr = 0,
    PcrFirst = (Pcr + 0),
    PcrLast = (PcrFirst + Other.First - 1),
}

public enum Derived : uint
{
    Half = (Constants.Bytes / 2),
    Sum = Constants.Bits + Other.First,
    Day = (uint)System.DayOfWeek.Friday + 1,
    LibraryDay = System.DayOfWeek.Friday + 1,
}

public abstract partial class StructureBase
{
    protected T CreateCopy<T>() where T : new() => new T();
}

public partial class Definition : StructureBase
{
    public Definition Copy() => CreateCopy<Definition>();
}

public partial class Definition : StructureBase
{
    public int Size;
}

public class Handle { }

public class SessionBase
{
    public static implicit operator Handle(SessionBase s) => new Handle();
}

public class AuthSession : SessionBase { }

public class Use
{
    private readonly Dictionary<Handle, AuthSession> sessions = new Dictionary<Handle, AuthSession>();
    private uint handle;

    public bool Has(AuthSession session) => sessions.ContainsKey(session);
    public bool Gone(AuthSession session) => sessions.Remove(session);
    public uint Index() { uint index = (handle & ~((0xff) << 24)); return index; }
    public uint Masked() { uint index = handle & 0xFFFF; return index; }

    // the errors that stay
    public uint Negative() { uint index = handle & -1; return index; }
    public Constants Outside() => Constants.Bits + Constants.Data;
    public bool Unrelated(Use other) => sessions.ContainsKey(other);
}
