/// <a><b>unclosed
class A { }
/// <a></b>
class B { }
/// <a x="1" x="2" y z="3"/>
class C { }
/// <a x="1 <b>"/> <a x="1"="2"/> <a ! x="1"/>
class D { }
/// text < not a tag
class E { }
/// </stray> text
class F { }
/// <a x=1/>
class G { }
/// <a x=“1”/> <see cref=“C”/> <param name=“p”/>
class H { }
/// <see cref="A B"/> <see cref="A<B>"/> <see cref="A.M(int"/> <see cref="operator"/> <see cref="operator ="/>
class I { }
/// <see cref="List{int}"/> <see cref="M(int,)"/> <see cref="M(,int)"/> <see cref="operator unchecked +"/>
class J { }
/// <a
class K { }
/// <a b="
class L { }
/// <!-- unterminated
class M { }
/// <![CDATA[ unterminated
class N { }
/// <?pi unterminated
class O { }
/// <see cref="A.
class P { }
/// <a></a b>
class Q { }
/// <a x="1"
class R { }
/// <see cref="M(ref readonly)"/> <see cref="M(out readonly int)"/> <see cref="a.b.c."/> <see cref="!"/>
class S { }
/// <:a/> <a:/> <a : b/> <1/> <a/ >
class T { }
