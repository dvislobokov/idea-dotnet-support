using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace Playground.Editor;

/// <summary>
/// Live check of the schema of <c>appsettings.json</c> made from the code (ROADMAP: «Схема конфигурации appsettings*.json из кода»,
/// steps 1–2). The classes below are bound to sections of the configuration in the ways the plugin looks for; the typing happens in
/// <c>Console/appsettings.json</c>, not here: each <c>// TYPE:name</c> marker says what to type there and what has to come out. Undo
/// the typing (Ctrl+Z) before the next marker. The schema is made by DotNetHelper in the background a moment after the file is opened
/// (and again a second after a .cs file changes): the first completion may come before it.
/// </summary>
public static class AppSettingsSchemaScenario
{
    public static void Bind(IServiceCollection services, IConfiguration configuration)
    {
        // way 1: Configure<T>(GetSection(constant))
        services.Configure<PositionOptions>(configuration.GetSection(PositionOptions.Position));

        // way 2: GetSection(nameof).Get<T>(), a nested section by chained GetSection
        var shop = configuration.GetSection(nameof(Shop)).Get<ShopOptions>();
        var retry = configuration.GetSection("Shop").GetSection("Retry").Get<RetryOptions>();

        // AddOptions<T>().BindConfiguration("A:B") and single values
        services.AddOptions<FeatureOptions>().BindConfiguration("Features:Beta");
        var timeout = configuration.GetValue<int>("Limits:TimeoutSeconds", 30);
        var greeting = configuration["Limits:Greeting"];
        Console.WriteLine($"{shop?.Name} {retry?.Count} {timeout} {greeting}");

        // TYPE:appsettings-keys — in Console/appsettings.json, caret inside the object of "Shop", Ctrl+Space.
        // EXPECT: Name, Mode, Timeout, Tags, Prices, Retry, Owner (with the text of the <summary> of each in the documentation popup);
        // not Secret (private setter) and not Total (no setter).

        // TYPE:appsettings-enum — in "Shop", type "Mode": and Ctrl+Space after the colon.
        // EXPECT: "Fast", "Cheap", "Balanced"; "Mode": "Slow" is highlighted as a value outside of the list.

        // TYPE:appsettings-wrong-type — in "Position", type "Height": "tall".
        // EXPECT: a warning on "tall" (the value has to be an integer); "Height": 5 and "Height": "5" are fine (the binder reads strings too).

        // TYPE:appsettings-timespan — in "Shop", type "Timeout": "soon".
        // EXPECT: a warning (a TimeSpan is [d.]hh:mm[:ss]); "Timeout": "00:00:30" and "Timeout": "1.02:00:00" are fine.

        // TYPE:appsettings-unknown-key — in "Position", type "Colour": "red".
        // EXPECT: a weak warning «PositionOptions has no property Colour» on the key (not the yellow of a warning); "title" in any case is
        // fine (keys ignore case); at the root a key nobody reads (e.g. "Whatever": 1) gives nothing, nor does a new key in "Limits" (a
        // section known only by GetValue paths is open).

        // TYPE:appsettings-dictionary — in "Shop": "Prices": { "apple": "x" }.
        // EXPECT: completion offers no keys inside Prices (any key goes), "x" is highlighted (a decimal is expected), 1.5 is fine.

        // TYPE:appsettings-marker — at the root, type "Cache" and Ctrl+Space inside its object.
        // EXPECT: "Redis" is offered; inside it SizeMb and Endpoints: the class CacheOptions is bound by the comment `// appsettings: Cache:Redis`.

        // TYPE:appsettings-base — at the root, "" and Ctrl+Space between the quotes; then "Default": in "Logging" → "LogLevel", Ctrl+Space.
        // EXPECT: Logging, Kestrel, AllowedHosts, ConnectionStrings next to Position, Shop, Features, Limits, Cache — from SchemaStore when
        // Settings | Languages & Frameworks | Schemas and DTDs | Remote JSON Schemas allows the network, from the plugin otherwise; the
        // status bar widget of the JSON schema shows «appsettings.json (.NET, sections of Console from its code)»; the log levels Trace …
        // None are offered for Default.

        // TYPE:appsettings-code-change — add a property `public int Weight { get; set; }` to ShopOptions, wait a second, Ctrl+Space in "Shop".
        // EXPECT: Weight is offered without saving the .cs file; remove the property — it is gone again.
    }
}

/// <summary>The position of the window on the screen.</summary>
public class PositionOptions
{
    public const string Position = "Position";

    /// <summary>The title of the window.</summary>
    public string Title { get; set; } = "Playground";

    /// <summary>Height in pixels.</summary>
    public int Height { get; set; } = 480;

    public bool Maximized { get; init; }
}

/// <summary>Settings of the shop.</summary>
public class ShopOptions
{
    /// <summary>Shown in the title bar.</summary>
    public string Name { get; set; } = "";

    /// <summary>How orders are shipped.</summary>
    public ShippingMode Mode { get; set; } = ShippingMode.Balanced;

    /// <summary>How long a checkout may take.</summary>
    public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(30);

    public List<string> Tags { get; set; } = ["new", "sale"];

    /// <summary>Price by the name of the product.</summary>
    public Dictionary<string, decimal> Prices { get; set; } = new();

    public RetryOptions Retry { get; set; } = new();

    public ShopOwner? Owner { get; set; }

    public string Secret { get; private set; } = "";

    public int Total => Prices.Count;
}

/// <summary>How a failed call is retried.</summary>
public class RetryOptions
{
    /// <summary>Attempts after the first one.</summary>
    public int Count { get; set; } = 3;

    public TimeSpan? Delay { get; set; }
}

public enum ShippingMode
{
    /// <summary>The next day.</summary>
    Fast,
    Cheap,
    Balanced,
}

public record ShopOwner(string Name, int Age = 30);

public class FeatureOptions
{
    public bool Enabled { get; set; }
    public Guid Id { get; set; }
}

// appsettings: Cache:Redis
public class CacheOptions
{
    public int SizeMb { get; set; } = 64;
    public string[] Endpoints { get; set; } = [];
}

/// <summary>Only the name of the section: <c>nameof(Shop)</c> above.</summary>
public static class Shop;
