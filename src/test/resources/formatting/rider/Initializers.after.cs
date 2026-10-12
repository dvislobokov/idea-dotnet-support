using System.Collections.Generic;

namespace Demo.Formatting
{
    public class Point
    {
        public int X { get; set; }
        public int Y { get; set; }
        public List<int> Items { get; } = new List<int>();
    }

    public record Pair(int A, int B);

    public class Initializers
    {
        private readonly int[] _field =
        [
            1,
            2
        ];

        public void Collections()
        {
            var list = new List<int>
            {
                1,
                2,
                3
            };
            var single = new List<int> { 1, 2, 3 };
            var spaced = new List<int> { 1, 2, 3 };
            var empty = new List<int> { };
            var next = new List<int>
            {
                1,
                2,
                3
            };
            var same = new List<int>
            {
                1,
                2,
                3
            };
            var rows = new List<int>
            {
                1, 2,
                3, 4
            };
            var trailing = new List<int>
            {
                1,
                2,
            };
            var dict = new Dictionary<string, int>
            {
                { "a", 1 },
                { "b", 2 }
            };
            var dictSingle = new Dictionary<string, int> { { "a", 1 }, { "b", 2 } };
            var nested = new List<List<int>>
            {
                new List<int>
                {
                    1,
                    2
                },
                new List<int>
                {
                    3
                }
            };
            var count = new List<int>
            {
                1,
                2
            }.Count;
        }

        public void Objects(Pair pair)
        {
            var p = new Point
            {
                X = 1,
                Y = 2
            };
            var pSingle = new Point { X = 1, Y = 2 };
            var pNext = new Point
            {
                X = 1,
                Y = 2
            };
            var together = new Point
            {
                X = 1, Y = 2
            };
            var inner = new Point
            {
                X = 1,
                Items =
                {
                    1,
                    2
                }
            };
            Point implicitNew = new()
            {
                X = 1,
                Y = 2
            };
            var copy = pair with
            {
                A = 1,
                B = 2
            };
            var anon = new
            {
                A = 1,
                B = 2
            };
            var anonSingle = new { A = 1, B = 2 };
            var anonRows = new
            {
                A = 1, B = 2,
                C = 3
            };
            var anonNested = new
            {
                Inner = new
                {
                    A = 1,
                    B = 2
                },
                C = 3
            };
        }

        public void Arrays()
        {
            var a = new[]
            {
                1,
                2,
                3
            };
            var aSingle = new[] { 1, 2, 3 };
            int[] b =
            {
                1,
                2
            };
            int[] bSingle = { 1, 2 };
            var c = new int[]
            {
                1,
                2
            };
            var jagged = new int[][]
            {
                new[] { 1, 2 },
                new[] { 3 }
            };
            var square = new int[,]
            {
                { 1, 2 },
                { 3, 4 }
            };
        }

        public int[] CollectionExpressions()
        {
            int[] e =
            [
                1,
                2,
                3
            ];
            int[] eSingle = [1, 2, 3];
            int[] eSpaced = [1, 2, 3];
            List<int> eNext =
            [
                1,
                2
            ];
            int[][] eNested =
            [
                [
                    1,
                    2
                ],
                [3]
            ];
            var sum = Sum([
                1,
                2
            ]);
            sum = Sum2(1, [
                1,
                2
            ]);
            sum = Sum2(
                1,
                [
                    1,
                    2
                ]);
            return
            [
                1,
                2
            ];
        }

        public int Sum(int[] a) => 0;

        public int Sum2(int x, int[] a) => 0;

        public void Call(Point p, int count)
        {
            Call(new Point
            {
                X = 1,
                Y = 2
            }, 1);
            Call(new Point { X = 1 }, new List<int>
            {
                1,
                2
            }.Count);
        }
    }
}
