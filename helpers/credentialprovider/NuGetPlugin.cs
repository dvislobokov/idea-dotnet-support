// NuGet's cross-platform plugin protocol (https://learn.microsoft.com/nuget/reference/extensibility/nuget-cross-platform-plugins), the part
// a credential provider needs. One JSON message per line both ways:
//   {"RequestId":"…","Type":"Request|Response|Fault|Cancel|Progress","Method":"Handshake|…","Payload":{…}}
// The handshake is symmetric: NuGet sends one and waits, besides our answer, for one of ours to answer (its SymmetricHandshake), so ours goes
// out right after we answer its own. The names and shapes are those of NuGet.Protocol 6.14 (NuGet.Protocol.Plugins.*Request / *Response).

using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json.Nodes;

namespace DotNetSupport.CredentialProvider;

public static class NuGetPlugin
{
    public const string ProtocolVersion = "2.0.0";

    public static async Task<int> RunAsync(Keyring keyring) => await new Session(keyring, Console.In, Console.Out).RunAsync();

    public sealed class Session(Keyring keyring, TextReader input, TextWriter output)
    {
        private readonly object _write = new();
        private bool _handshakeSent;
        /** The sources answered in this process: NuGet keeps the plugin for its whole run, and a retry after our answer means it was refused. */
        private readonly ConcurrentDictionary<string, bool> _answered = new();

        public async Task<int> RunAsync()
        {
            while (await input.ReadLineAsync() is { } line)
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                JsonObject? message;
                try { message = JsonNode.Parse(line) as JsonObject; }
                catch (System.Text.Json.JsonException) { continue; }
                if (message == null || (string?)message["Type"] != "Request") continue;
                var id = (string?)message["RequestId"] ?? "";
                var method = (string?)message["Method"] ?? "";
                if (method == "Close") return 0;
                var payload = message["Payload"] as JsonObject ?? new JsonObject();
                Send(id, "Response", method, Answer(method, payload));
                if (method == "Handshake" && !_handshakeSent)
                {
                    _handshakeSent = true;
                    Send(Guid.NewGuid().ToString(), "Request", "Handshake", new JsonObject { ["ProtocolVersion"] = ProtocolVersion, ["MinimumProtocolVersion"] = "1.0.0" });
                }
            }
            return 0;
        }

        public JsonObject Answer(string method, JsonObject payload)
        {
            switch (method)
            {
                case "Handshake":
                    var minimum = (string?)payload["MinimumProtocolVersion"] ?? "1.0.0";
                    return Version.TryParse(minimum, out var min) && min <= Version.Parse(ProtocolVersion)
                        ? new JsonObject { ["ResponseCode"] = "Success", ["ProtocolVersion"] = ProtocolVersion }
                        : new JsonObject { ["ResponseCode"] = "Error" };
                case "GetOperationClaims":
                    // authentication is a claim of no particular source: NuGet asks it with neither the source nor its service index
                    var agnostic = payload["PackageSourceRepository"] == null && payload["ServiceIndex"] == null;
                    return new JsonObject { ["Claims"] = agnostic ? new JsonArray("Authentication") : new JsonArray() };
                case "GetAuthenticationCredentials":
                    return Credentials(payload);
                case "MonitorNuGetProcessExit":
                    if (payload["ProcessId"]?.GetValue<int>() is { } pid) _ = ExitWith(pid);
                    return Success();
                case "Initialize":
                case "SetLogLevel":
                case "SetCredentials":
                    return Success();
                default:
                    return new JsonObject { ["ResponseCode"] = "NotFound" };
            }
        }

        private JsonObject Credentials(JsonObject payload)
        {
            var uri = (string?)payload["Uri"];
            if (string.IsNullOrEmpty(uri)) return new JsonObject { ["ResponseCode"] = "NotFound" };
            var key = SourceKey.Of(uri);
            if (payload["IsRetry"]?.GetValue<bool>() == true && _answered.ContainsKey(key))
            {
                Log("Warning", $"The feed {uri} refused the user and password C# Project Support keeps for it in the keychain: update them in the IDE (NuGet → Sources → Edit).");
                return new JsonObject { ["ResponseCode"] = "NotFound" };
            }
            StoredCredentials? found;
            try
            {
                found = keyring.Lookup(key);
            }
            catch (KeyringException e)
            {
                // not an Error: NuGet would stop the restore of every feed that answers 401, ours or not; another provider may know it
                Log("Warning", $"C# Project Support could not read the keychain for {uri}: {e.Message}");
                return new JsonObject { ["ResponseCode"] = "NotFound" };
            }
            if (found == null) return new JsonObject { ["ResponseCode"] = "NotFound" };
            _answered[key] = true;
            return new JsonObject
            {
                ["Username"] = found.Username, ["Password"] = found.Password, ["AuthenticationTypes"] = new JsonArray("Basic"), ["ResponseCode"] = "Success",
            };
        }

        private static JsonObject Success() => new() { ["ResponseCode"] = "Success" };

        /** A line in NuGet's output (`warn : …` of `dotnet restore`); NuGet answers it, the answer is not waited for. */
        private void Log(string level, string message) =>
            Send(Guid.NewGuid().ToString(), "Request", "Log", new JsonObject { ["LogLevel"] = level, ["Message"] = message });

        private void Send(string id, string type, string method, JsonObject payload)
        {
            var line = new JsonObject { ["RequestId"] = id, ["Type"] = type, ["Method"] = method, ["Payload"] = payload }.ToJsonString();
            lock (_write)
            {
                output.WriteLine(line);
                output.Flush();
            }
        }

        private static async Task ExitWith(int pid)
        {
            try { await Process.GetProcessById(pid).WaitForExitAsync(); }
            catch (ArgumentException) { }
            Environment.Exit(0);
        }
    }
}
