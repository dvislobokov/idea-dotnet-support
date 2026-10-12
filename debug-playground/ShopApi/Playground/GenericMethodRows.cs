using Shop.Api.Domain;

namespace Shop.Api.Playground;

// Generic methods in the completion list after a dot (0.1.148): one row per name and arity, as in Rider. Type on the empty line under a
// marker, look at the list, press Enter, look at the text, then undo with Ctrl+Z: the file compiles as is.
public static class GenericMethodRows
{
    public static void Register(IServiceCollection services, List<Order> orders)
    {
        // TYPE:generic-rows — type `services.AddSin`. EXPECT: three rows of AddSingleton: `AddSingleton(Type serviceType, Type implementationType) (+ N)`,
        // `AddSingleton<TService>(…) (+ N)`, `AddSingleton<TService, TImplementation>(…) (+ N)`, each with its own count; the generic ones show the
        // names of the type parameters, not `<>`. Enter on `AddSingleton<TService>` writes `services.AddSingleton<|>();` with the caret between the angle brackets.

        // TYPE:generic-inferred — type `orders.Sel`. EXPECT: `Select<TResult>(Func<Order, TResult> selector) (+ 1)`; Enter writes `orders.Select(|)`
        // without `<>`: the type argument comes from the lambda. Same for `orders.OfT` → `OfType<TResult>()`, which writes `OfType<|>()`.

        _ = (services, orders);
    }
}
