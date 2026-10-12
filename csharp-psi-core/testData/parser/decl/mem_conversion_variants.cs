class C
{
    implicit operator int(C c) => 0;
    public static implicit int(C c) => 0;
    public static implicit
    C x;
    explicit operator C(int i);
    public static explicit operator unchecked int(C c) => 0;
    public static implicit operator checked int(C c) => 0;
}
