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
