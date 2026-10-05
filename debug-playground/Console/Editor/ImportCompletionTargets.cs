// Types and extension methods for the TYPE:import-neighbour-* markers in ImportCompletion.cs (0.1.87): namespaces next to the
// file's own that it does not import, so completion offers them as «Receipt (in Playground.ImportCompletionTargets.Billing)».
namespace Playground.ImportCompletionTargets.Billing
{
    public class Receipt
    {
        public decimal Total { get; set; }
    }

    public class ReceiptBook<T>
    {
        public List<T> Pages { get; } = new();
    }
}

namespace Playground.ImportCompletionTargets.Text
{
    public static class TextExtras
    {
        public static string Yell(this string text) => text.ToUpperInvariant() + "!";

        public static IEnumerable<T> EveryOther<T>(this IEnumerable<T> items) => items.Where((_, index) => index % 2 == 0);
    }
}
