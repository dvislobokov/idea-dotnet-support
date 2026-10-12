// CS0121: The call is ambiguous between the following methods or properties: 'M(A)' and 'M(B)'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0121;

public class Shape { }
public class Circle : Shape { }

public static class TextTools
{
    public static void Tag(this string text, object value) { }
    public static void Stamp(this string text, int value) { }
    public static void Stamp(this string text, long value) { }
}

public static class MoreTextTools
{
    public static void Tag(this string text, object value) { }
}

public class Painter
{
    public virtual void Fill(int color, long alpha) { }
    public virtual void Fill(long color, int alpha) { }
    public void Stroke(int width, long dash) { }
}

public class Brush : Painter
{
    public override void Fill(long color, int alpha) { }
    public void Stroke(long width, int dash) { }
}

public class Mixer
{
    public void Mix(int a, long b) { }
    public void Mix(long a, int b) { }

    public static void Log(object message, string category) { }
    public static void Log(string message, object category) { }

    public void Draw(Shape shape, Circle circle) { }
    public void Draw(Circle circle, Shape shape) { }

    public void Pick(int value) { }
    public void Pick(long value) { }

    public void Place<T>(T item, int slot) { }
    public void Place<T>(int slot, T item) { }
    public void Put<T>(T value) { }
    public void Put(int value) { }

    public void Total(params int[] values) { }
    public void Total(params long[] values) { }
    public void Spread(int first, params long[] rest) { }
    public void Spread(long first, params int[] rest) { }
    public void Words(params string[] words) { }
    public void Words(params object[] words) { }
    public void Sum(int a, int b) { }
    public void Sum(params int[] values) { }

    public void Resize(int width, int height = 0) { }
    public void Resize(int width, long height = 0) { }
    public void Move(int x) { }
    public void Move(int x, int y = 0) { }

    public void Swap(ref int a, int b, long c) { }
    public void Swap(ref int a, long b, int c) { }
    public void Read(in int value) { }
    public void Read(int value) { }

    public void OnEvent(Action<int> handler) { }
    public void OnEvent(Action<string> handler) { }
    public void Compute(Func<int> producer) { }
    public void Compute(Func<long> producer) { }
    public void Map(Func<int, int> map) { }
    public void Map(Func<int, long> map) { }
    static int Twice(int x) => x * 2;

    public void Use(Mixer other, Circle circle, Shape shape, Brush brush, string text)
    {
        Mix(1, 1); // ERROR CS0121
        other.Mix(2, 3); // ERROR CS0121
        Mix(1, 1L);
        Mix(1L, 1);
        Log(null, null); // ERROR CS0121
        Mixer.Log("text", "area"); // ERROR CS0121
        Log("text", 1);
        Log((object)"text", "area");
        Draw(circle, circle); // ERROR CS0121
        Draw(shape, circle);
        Draw(circle, shape);
        Pick(1);
        Pick(1L);
        Place(circle, 2);
        Place(1, 2); // ERROR CS0121
        Put(1);
        Put("one");
        Total(1, 2);
        Spread(1, 1); // ERROR CS0121
        Spread(1, 1L);
        Total(); // ERROR CS0121
        Total(1L, 2L);
        Words();
        Sum(1, 2);
        Resize(10); // ERROR CS0121
        Resize(10, 20);
        Move(1);
        int size = 1;
        Swap(ref size, 1, 1); // ERROR CS0121
        Swap(ref size, 1, 1L);
        Read(size);
        Read(in size);
        OnEvent(x => { }); // ERROR CS0121
        OnEvent((int x) => { });
        Compute(() => 1);
        Map(Twice);
        brush.Fill(1, 1); // ERROR CS0121
        brush.Fill(1, 1L);
        brush.Stroke(1, 1);
        text.Tag(1); // ERROR CS0121
        text.Stamp(1);
        Math.Round(1); // ERROR CS0121
        Math.Round(1.5);
    }
}
