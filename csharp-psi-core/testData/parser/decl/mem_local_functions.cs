class C
{
    void M()
    {
        void L1() { }
        int L2<T>(T t) where T : struct => 0;
        async Task L3() { await Task.Yield(); }
        static extern void L4();
        unsafe int* L5() => null;
        await L6();
        int L7(int x) => x
        L8();
    }
}
