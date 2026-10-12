#region R
#if DEBUG // comment
class A { }
#else
class B { }
#endif
  #pragma warning disable CS0168
x; #define Y
#endregion
