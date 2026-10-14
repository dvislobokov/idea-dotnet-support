// docs: -; codes: CS1061 CS0029 (0.1.184, EFCore.BulkExtensions of a live project: DbType.Binary.ToString() was CS1061; the members of object on an enum, a struct, a delegate of an assembly)
using System;

static class Probe
{
    static void Members(string columnType, DayOfWeek day, Guid id, Action action)
    {
        var a = DayOfWeek.Monday.ToString();
        var b = columnType.Contains(StringComparison.Ordinal.ToString(), StringComparison.OrdinalIgnoreCase);
        var c = day.GetType().Name + day.GetHashCode() + day.HasFlag(DayOfWeek.Sunday) + day.CompareTo(DayOfWeek.Friday);
        var d = id.GetType().Name + TimeSpan.Zero.GetType().Name;
        var e = action.GetInvocationList().Length + action.Method.Name.Length + action.GetType().Name;
        Enum boxedDay = day;
        ValueType boxedId = id;
        Delegate anyAction = action;
        MulticastDelegate manyActions = action;
        object o = boxedDay;
        var f = day.Nope;
        int g = boxedDay;
    }
}
