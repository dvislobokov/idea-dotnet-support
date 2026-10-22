// The NuGet credential provider of the plugin (nuget/NuGetKeychain in the plugin): the user and the password of a private feed are kept in
// the keychain of the OS (Keyring.cs), and NuGet asks this program for them after a 401 of the feed — in `dotnet restore` of the IDE and
// of any terminal alike, with nothing in nuget.config. Two ways to run it:
//
//   dotnet CredentialProvider.DotNetSupport.dll -Plugin      NuGet's cross-platform plugin protocol over stdin/stdout (below)
//   dotnet CredentialProvider.DotNetSupport.dll <command>    the plugin: one JSON object on stdin, the answer on stdout
//       store  {"source":"<url>","username":"…","password":"…"}   → exit 0
//       get    {"source":"<url>"}                                  → {"username":"…","password":"…"}, exit 0; exit 3 when none is stored
//       erase  {"source":"<url>"}                                  → exit 0 (nothing stored is no error)
//       check                                                      → exit 0 when the keychain can be used
//   Exit 2 with the reason on stderr: the keychain cannot be used (no Secret Service on Linux, a locked keychain…).
//
// The password never goes to the command line: it would be seen in the list of processes.

using System.Text.Json;
using System.Text.Json.Nodes;

namespace DotNetSupport.CredentialProvider;

public static class Program
{
    public const int NotFound = 3;
    public const int Unusable = 2;

    public static async Task<int> Main(string[] args)
    {
        // the protocol is UTF-8 lines; the console of Windows would encode in its code page
        Console.InputEncoding = new System.Text.UTF8Encoding(false);
        Console.OutputEncoding = new System.Text.UTF8Encoding(false);
        if (args.Any(a => a.Equals("-Plugin", StringComparison.OrdinalIgnoreCase))) return await NuGetPlugin.RunAsync(Keyring.Create());
        if (args.Length == 0)
        {
            Console.Error.WriteLine("The NuGet credential provider of C# Project Support. NuGet runs it with -Plugin; the IDE with store | get | erase | check.");
            return 1;
        }
        try
        {
            var keyring = Keyring.Create();
            switch (args[0])
            {
                case "check":
                    keyring.Check();
                    return 0;
                case "store":
                {
                    var request = ReadRequest();
                    keyring.Store(SourceKey.Of(Required(request, "source")), new StoredCredentials(Required(request, "username"), Required(request, "password")));
                    return 0;
                }
                case "get":
                {
                    var found = keyring.Lookup(SourceKey.Of(Required(ReadRequest(), "source")));
                    if (found == null) return NotFound;
                    Console.Out.Write(new JsonObject { ["username"] = found.Username, ["password"] = found.Password }.ToJsonString());
                    return 0;
                }
                case "erase":
                    keyring.Erase(SourceKey.Of(Required(ReadRequest(), "source")));
                    return 0;
                default:
                    Console.Error.WriteLine($"Unknown command {args[0]}");
                    return 1;
            }
        }
        catch (KeyringException e)
        {
            Console.Error.WriteLine(e.Message);
            return Unusable;
        }
    }

    private static JsonObject ReadRequest() =>
        JsonNode.Parse(Console.In.ReadToEnd()) as JsonObject ?? throw new KeyringException("The request on stdin is not a JSON object");

    private static string Required(JsonObject request, string name) =>
        request[name]?.GetValue<string>() is { Length: > 0 } value ? value : throw new KeyringException($"The request has no {name}");
}

/** What is kept for a feed: the secret of the keychain item is this as JSON, so one lookup by the source gives both. */
public sealed record StoredCredentials(string Username, string Password)
{
    public string ToJson() => new JsonObject { ["Username"] = Username, ["Password"] = Password }.ToJsonString();

    public static StoredCredentials? FromJson(string json)
    {
        try
        {
            var node = JsonNode.Parse(json) as JsonObject;
            var user = node?["Username"]?.GetValue<string>();
            var password = node?["Password"]?.GetValue<string>();
            return user != null && password != null ? new StoredCredentials(user, password) : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}

/**
 * The key a feed is kept under: NuGet asks with the URL of the source as nuget.config has it, the plugin stores with the URL the user typed;
 * both go through the same normalization here (`HTTPS://Feed/v3/index.json/` and `https://feed/v3/index.json` are one feed).
 */
public static class SourceKey
{
    public static string Of(string source)
    {
        var trimmed = source.Trim();
        var text = Uri.TryCreate(trimmed, UriKind.Absolute, out var uri) ? uri.AbsoluteUri : trimmed;
        return text.TrimEnd('/').ToLowerInvariant();
    }
}
