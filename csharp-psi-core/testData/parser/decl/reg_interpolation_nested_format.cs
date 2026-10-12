class A
{
    void M()
    {
        W($"\t{string.Join(", ", o.Select(x => $"T: {x.t:HH:mm:ss.fffffff}, D: {x.d}"))}");
    }
}
