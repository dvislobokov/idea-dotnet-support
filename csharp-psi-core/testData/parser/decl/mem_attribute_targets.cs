class C
{
    [field: A] int P { get; set; }
    [method: A, return: B] void M() { }
    [type: A] class D { }
    [param: A] [typevar: B] void N<[C] T>([D] T t) { }
}
