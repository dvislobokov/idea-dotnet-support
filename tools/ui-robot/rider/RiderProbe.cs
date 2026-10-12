using System.Text;

namespace Playground.Editor;

// Probe for the UI robot in Rider: what Rider suggests at each empty line (line numbers are used by the scripts).
public class RiderProbe
{
    public Task<string> NotAsync(int id)
    {

    }

    public Task Plain()
    {

    }

    public async Task<int> WithToken(HttpClient client, CancellationToken cancellationToken)
    {

        return 0;
    }

    public async Task<int> WithoutToken(Stream stream)
    {

        return 0;
    }

    public void Body(string? name, List<int> items)
    {

    }

    public async Task Awaits()
    {

    }
}
