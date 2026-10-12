// CS0557: Duplicate user-defined conversion in type 'T'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0557;

public readonly struct Celsius(double degrees)
{
    public double Degrees => degrees;
    public static implicit operator double(Celsius value) => value.Degrees;
    public static explicit operator double(Celsius value) => value.Degrees; // ERROR CS0557
    public static implicit operator Celsius(double degrees) => new(degrees);
    public static implicit operator Celsius(double value) => new(value); // ERROR CS0557
    public static explicit operator int(Celsius value) => (int)value.Degrees;
    public static explicit operator long(Celsius value) => (long)value.Degrees;
    public static explicit operator Celsius(int degrees) => new(degrees);
    public static explicit operator Celsius(long degrees) => new(degrees);
    public static explicit operator decimal(Celsius value) => (decimal)value.Degrees;
    public static explicit operator decimal(Celsius other) => 0m; // ERROR CS0557
}

public class Token(string text)
{
    public string Text => text;
    public static implicit operator string(Token token) => token.Text;
    public static explicit operator string(Token token) => token.Text; // ERROR CS0557
    public static explicit operator Token(string text) => new(text);
    public static explicit operator char[](Token token) => token.Text.ToCharArray();
    public static explicit operator List<char>(Token token) => token.Text.ToList();
    public static explicit operator List<char>(Token other) => new(); // ERROR CS0557
    public static explicit operator List<int>(Token token) => new();
}

public class Wrapper<T>(T value)
{
    public T Value => value;
    public static implicit operator T(Wrapper<T> wrapper) => wrapper.Value;
    public static explicit operator T(Wrapper<T> other) => other.Value; // ERROR CS0557
    public static implicit operator Wrapper<T>(T item) => new(item);
}
