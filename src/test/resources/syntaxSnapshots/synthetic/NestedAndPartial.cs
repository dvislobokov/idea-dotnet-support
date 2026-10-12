namespace Synthetic.Nested;

public partial class Outer
{
    public class Inner
    {
        private class Deepest
        {
            public void Leaf() { }
        }

        public int Value { get; set; }
    }

    protected internal struct Point { public int X; }

    public enum Mode : byte { First = 1, [Obsolete] Second, Third = First | Second }

    static Outer() { }

    ~Outer() { }

    public static Outer operator +(Outer a, Outer b) => a;

    public static implicit operator int(Outer o) => 0;

    public int this[int index] { get => index; set { } }

    public event EventHandler Changed
    {
        add { }
        remove { }
    }
}

public partial class Outer
{
    public void SecondPart()
    {
        void Local() { }
        Local();
    }
}

file class OnlyHere { }
