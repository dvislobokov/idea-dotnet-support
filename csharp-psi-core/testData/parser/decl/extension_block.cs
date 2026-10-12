static class E
{
    extension(int i)
    {
        public int P => i;
    }
    extension<T>(T) where T : class { }
}
