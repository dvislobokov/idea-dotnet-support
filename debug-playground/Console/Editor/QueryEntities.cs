using System.Collections.Generic;

// The entities of TYPE:queries-inherited-member (Queries.cs): their members come from an abstract base whose simple name is also the name
// of a DTO the query's file imports — the base is the entity's own, as the compiler looks it up here, not the DTO (E-190).
namespace Playground.Editor.QueryEntities
{
    public abstract class QueryEntity<TKey>
    {
        public TKey Id { get; set; } = default!;
    }

    public abstract class QueryStressTest : QueryEntity<long>
    {
        public DateOnly BookDate { get; set; }
        public int CalculationTypeId { get; set; }
    }

    public sealed class DirectQueryStressTest : QueryStressTest
    {
        public string? Comment { get; set; }
    }

    /// <summary>As the project's own DbContext: its sets are of the entities above.</summary>
    public sealed class QueryStressContext
    {
        public QueryDbSet<DirectQueryStressTest> DirectStressTests { get; } =
            new(new List<DirectQueryStressTest> { new() { Id = 1, BookDate = new DateOnly(2002, 1, 1), CalculationTypeId = 7 } });
    }
}

namespace Playground.Editor.QueryRequests
{
    /// <summary>A DTO named as the entities' base: Queries.cs imports this namespace, the entities do not.</summary>
    public sealed class QueryStressTest
    {
        public string Title { get; set; } = "";
    }
}
