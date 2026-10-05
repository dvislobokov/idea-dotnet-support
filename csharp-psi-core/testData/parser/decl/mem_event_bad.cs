class C
{
    event System.Action;
    event E;
    event System.Action A.B;
    event System.Action A.B { add { } }
    event System.Action X { add { } };
    event System.Action Y = null, Z = null;
}
