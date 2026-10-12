// CS8646: 'I.M()' is explicitly implemented more than once. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS8646;

public interface IRepository
{
    void Save(int id);
    void Save(string key);
    int Count { get; }
    string this[int index] { get; }
    event Action Changed;
}

public interface IDisposableResource
{
    void Release();
}

public class Repository : IRepository, IDisposableResource // ERROR CS8646
{
    void IRepository.Save(int id) { }
    void IRepository.Save(int key) { } // ERROR CS0111
    void IRepository.Save(string key) { }
    int IRepository.Count => 0;
    string IRepository.this[int index] => "";
    event Action IRepository.Changed { add { } remove { } }
    void IDisposableResource.Release() { }
    public void Save(int id) { }
    public void Release() { }
}

public class Cache : IRepository // ERROR CS8646
{
    void IRepository.Save(int id) { }
    void IRepository.Save(string key) { }
    int IRepository.Count => 1;
    int IRepository.Count => 2; // ERROR CS0102
    string IRepository.this[int index] => "";
    event Action IRepository.Changed { add { } remove { } }
}

public class Index : IRepository // ERROR CS8646
{
    void IRepository.Save(int id) { }
    void IRepository.Save(string key) { }
    int IRepository.Count => 1;
    string IRepository.this[int index] => "";
    string IRepository.this[int position] => ""; // ERROR CS0111
    event Action IRepository.Changed { add { } remove { } }
    public string this[int index] => "";
}
