using System;
using System.IO;
using System.Linq;

namespace Demo.Formatting
{
    public class Arguments
    {
        public Arguments(int a,
int b, int c) : this(a,
b)
        {
            Foo(a, b);
        }

        public Arguments(int a, int b)
        {
            Foo(a, b);
        }

        public int this[int a,
int b] => a;

        public int Foo(int a, int b) => a + b;

        public int Foo3(int a, int b, int c) => a + b + c;

        public void Method(int first,
int second,
              int third)
        {
            Foo(first, second);
        }

        public void Method2(
int first,
int second
)
        {
            Foo(first, second);
        }

        public int Expression(int a,
int b) => Foo(a,
b);

        [Obsolete("a",
false)]
        public void Attributed()
        {
            Foo(1, 2);
        }

        public void Calls(bool flag)
        {
            Foo(1,
2);
            Foo(1,
                    2);
            Foo(
1,
2);
            var x = Foo(1,
2);
            x = Foo3(1,
            Foo(2,
3),
4);
            x = Foo(1, Foo(2,
3));
            x = Foo(Foo(1,
2), 3);
            x = Foo(Foo(1,
2),
3);
            x = Foo(Foo(Foo(1,
2),
3),
4);
            x = Foo(1,
2) + Foo(3,
4);
            var created = new Arguments(1,
2);
            var element = this[1,
2];
            var chained = this.Foo(1,
2).ToString();
            x = Foo(1,
2
);
            x = Foo(1, // first
2);
            x = this
                .Foo(1,
2);
            var s = string.Format("{0} {1}",
1,
2);
            Func<int, int, int> f = (a,
b) => a + b;
            var items = Enumerable.Range(0, 3).Select(i => Foo(i,
1)).ToList();
            Run(() =>
            {
                Foo(1,
2);
            }, 3);
            while (Foo(1,
2) > 3)
                x++;
            using (var stream = new MemoryStream(new byte[]{1,
2}))
                x++;
        }

        public void Run(Action action, int count)
        {
            action();
        }
    }
}
