using System;
using System.Collections.Generic;

namespace Synthetic.Generics
{
    public sealed class Map<TKey, TValue> : Dictionary<TKey, List<TValue>>, IDisposable where TKey : notnull where TValue : class, new()
    {
        private readonly Dictionary<string, List<int>> _cache = new();
        private int _first, _second;
        private Func<int, Dictionary<string, int>> _factory = x => new Dictionary<string, int> { ["a"] = x };

        public TValue Get<TResult>(TKey key, Func<TValue, TResult> map) where TResult : struct
        {
            return default!;
        }

        public IEnumerable<KeyValuePair<TKey, TValue>> Pairs() => throw new NotImplementedException();

        public (int Count, string Name) Tuple() => (1, "a");

        public List<(int, string)> Tuples { get; } = new();

        void IDisposable.Dispose() { }
    }

    public interface IConverter<in TIn, out TOut>
    {
        TOut Convert(TIn value);
    }

    public delegate TResult Transformer<T, TResult>(T input) where T : class;

    public struct Pair<T> where T : struct
    {
        public T Left, Right;
    }
}
