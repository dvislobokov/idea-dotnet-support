// Types for TYPE:import-type-choice in ImportType.cs (0.1.74): one name in two namespaces nobody imports, so «Import type 'Canvas'…» asks which.
namespace Playground.ImportTargets.Drawing
{
    public class Canvas
    {
        public int Width { get; set; }
    }
}

namespace Playground.ImportTargets.Printing
{
    public class Canvas
    {
        public int Pages { get; set; }
    }
}

// Types for TYPE:import-stats-* in ImportType.cs (0.1.138): a name the corpus knows (`JObject`) in two namespaces that are real in the corpus, so
// the statistics, not the alphabet, decide the order (Newtonsoft.Json.Linq is where JObject lives; the project references no Newtonsoft package).
namespace Newtonsoft.Json
{
    public class JObject
    {
        public int Depth { get; set; }
    }
}

namespace Newtonsoft.Json.Linq
{
    public class JObject
    {
        public int Count { get; set; }
    }
}
