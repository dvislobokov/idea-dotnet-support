// docs: cs8334.md #2; codes: CS8334
class Program
{
    static ref readonly int M(in int arg1, in (int Alice, int Bob) arg2)
    {
        return ref arg2.Alice;
    }
}
