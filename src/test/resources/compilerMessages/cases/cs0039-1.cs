// docs: cs0039.md #1; codes: CS0039
using System;

class A { }
class B : A { }
class C : A { }

class Example
{
    static void Main()
    {
        C c;

        // This compiles, because
        // there is an explicit reference conversion from type A to type C.
        A a = new C();
        c = a as C;

        // This generates CS0039, because
        // there is no implicit or explicit reference conversion between B and C types.
        B b = new B();
        c = b as C;  // CS0039
    }
}
