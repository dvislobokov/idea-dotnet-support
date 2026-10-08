// docs: cs0051.md #2; codes: CS0051
// Another file or assembly.
internal class DatabaseConfiguration
{
    public string ConnectionString { get; set; }
}

// In your main class.
public class DataService
{
    // This causes CS0051 because the constructor is public.
    // but DatabaseConfiguration is internal.
    public DataService(DatabaseConfiguration config)  // CS0051
    {
        // Implementation.
    }
}
