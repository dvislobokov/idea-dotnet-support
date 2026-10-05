using System;
using System.Collections.Generic;
using System.Linq;
namespace Demo.Formatting
{
    public class Basics : IDisposable
    {
        private int _count = 0;
        private readonly List<string> _names = new List<string> { "a", "b" };
        public int Count { get { return _count; } set { _count = value; } }
        public string Name { get; set; } = "x";


        public Basics(int count) : base()
        {
            _count = count * 2 + 1;
        }
        public void Dispose() { }
        public int Sum(int a, int b)
        {
            if (a > b) { return a - b; }
            else if (a == b)
                return 0;
            else
            {
                return b - a;
            }
        }
        public async System.Threading.Tasks.Task<int> LoopAsync(IEnumerable<int> items)
        {
            int total = 0;
            foreach (var item in items) { total += item; }
            for (int i = 0; i < 10; i++) total++;
            while (total > 100) { total--; }
            do { total++; } while (total < 5);
            try
            {
                total = checked(total * 2);
            }
            catch (OverflowException e) when (e.Message != null)
            {
                total = -1;
            }
            finally
            {
                total += 1;
            }
            using (var d = new Basics(1)) { total += d.Count; }
            lock (_names) { total++; }
            await System.Threading.Tasks.Task.Delay(1);
            return total;
        }
        public string Describe(object o) => o switch
        {
            int i when i > 0 => "positive",
            string s => s,
            _ => "other"
        };
        public string Lambda()
        {
            Func<int, int> twice = x => x * 2;
            Action<string> print = s =>
            {
                Console.WriteLine(s);
            };
            var q = from n in _names
                    where n != null
                    let len = n.Length
                    select new { Name = n, Length = len };
            var anon = new { A = 1, B = "two" };
            var obj = new Basics(2) { Count = 3, Name = "n" };
            int[] arr = { 1, 2, 3 };
            var list = new List<int>
            {
                1,
                2,
                3
            };
            return $"{twice(2)}{anon.A} and {string.Join(",", arr)}";
        }
    }
}
