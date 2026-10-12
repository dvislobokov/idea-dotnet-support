class C
{
    static implicit I.operator int(C c) => 0;
    static implicit I::operator int(C c) => 0;
    static explicit N.I<T>.operator int(C c) => 0;
}
