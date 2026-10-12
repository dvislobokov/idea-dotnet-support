using System;
using System.Collections.Generic;
using App.Model;

namespace App
{
    // The input of the semantic gate's unit tests (SemanticDumpTest); its dump is sample.txt next to the folder App.
    class Orders : Base
    {
        private readonly List<Order> items = new();

        public int Count => items.Count;

        public decimal Total(decimal discount)
        {
            var sum = 0m;
            foreach (var order in items)
                sum += order.Price;
            Func<int, int> twice = x => x * 2;
            Log("total " + twice(2));
            if (sum < 0) goto done;
            sum -= discount;
        done:
            return sum + Missing;
        }

        string Describe() => Name.ToUpperInvariant() + 1.5 + 10L + 'c' + true;
    }
}
