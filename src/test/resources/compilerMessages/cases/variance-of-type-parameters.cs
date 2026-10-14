// docs: -; codes: CS1503 (0.1.179, EFCore.BulkExtensions of a live project: IList<T> to IEnumerable<object> is variance when T is a reference type only)
using System;
using System.Collections.Generic;

class Entity { }
record Row(int Id);

static class Bulk
{
    static void Insert(IEnumerable<object> entities) { }
    static void Act(Action<string> action) { }

    static void ByClass<T>(IList<T> entities) where T : class => Insert(entities);
    static void ByNullableClass<T>(IList<T> entities) where T : class? => Insert(entities);
    static void ByBaseClass<T>(IList<T> entities) where T : Entity => Insert(entities);
    static void ByRecord<T>(IList<T> entities) where T : Row => Insert(entities);
    static void ByException<T>(List<T> entities) where T : Exception => Insert(entities);
    static void ByOtherParameter<T, U>(IList<T> entities) where T : U where U : class => Insert(entities);
    static void Contravariant<T>(Action<T> action) where T : class => Act(action as Action<string>);

    static void Unconstrained<T>(IList<T> entities) => Insert(entities);
    static void ByInterface<T>(IList<T> entities) where T : IDisposable => Insert(entities);
    static void ByStruct<T>(IList<T> entities) where T : struct => Insert(entities);
    static void ByEnum<T>(IList<T> entities) where T : Enum => Insert(entities);
}
