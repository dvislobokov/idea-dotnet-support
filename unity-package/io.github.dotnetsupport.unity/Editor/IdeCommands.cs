// Commands of the IDE that live in other assemblies of the package (the tests, which compile only with com.unity.test-framework): they
// register here by the first word of the command, and IdeConnection gives them the rest of its text.
using System;
using System.Collections.Generic;

namespace DotNetSupport.Unity.Editor
{
    public static class IdeCommands
    {
        private static readonly Dictionary<string, Action<string>> Handlers = new Dictionary<string, Action<string>>();

        /// <summary>[handler] gets the text after `name ` (may span lines); registered again on every reload of the domain.</summary>
        public static void Register(string name, Action<string> handler) => Handlers[name] = handler;

        internal static bool TryRun(string command)
        {
            var space = command.IndexOfAny(new[] { ' ', '\n' });
            var name = space < 0 ? command : command.Substring(0, space);
            if (!Handlers.TryGetValue(name, out var handler)) return false;
            handler(space < 0 ? "" : command.Substring(space + 1));
            return true;
        }
    }
}
