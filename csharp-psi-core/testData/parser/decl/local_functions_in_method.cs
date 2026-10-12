class C
{
    void M()
    {
        int F(int x) => x;
        static async Task G<T>(T t) where T : class { await t; }
        [A] void H() { }
        F(1);
    }
}
