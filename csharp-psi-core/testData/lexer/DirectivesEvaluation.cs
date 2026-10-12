#define A
#undef B
#if A && !B
class Taken1 { }
#elif true
class NotTaken1 { }
#else
class NotTaken2 { }
#endif
#if B || (A == false) || A != true
class NotTaken3 { }
#elif A == (B == false)
class Taken2 { }
#elif A
class NotTaken4 { }
#endif
#if TRUE && true && !False && !C
class Taken3 { }
#endif
#if X
#define D
#elif D
class QuirkInactiveDefineSeenByElif { }
#endif
#if D
class NotTaken5 { }
#endif
#if false
  #if A
  class NotTaken6 { }
  #else
  class NotTaken7 { }
  #endif
  #region inside
  #endregion
  #pragma warning disable CS1
  #define E
  #error not reported
#elif A
class Taken4 { }
#if E
class NotTaken8 { }
#endif
#endif
#undef A
#if A
class NotTaken9 { }
#else
class Taken5 { }
#endif
#if
class NotTaken10 { }
#endif
#if A ==
class Taken6 { }
#endif
#if (A
class NotTaken11 { }
#endif
#if A B
class NotTaken12 { }
#endif
#region R
#if !A
class Taken7 { }
#endregion
#endif
#endregion
#if !A
class Taken8 { }
#else
class NotTaken13 { }
#else
class NotTaken14 { }
#endif
#else
class AfterBadElse { }
#endif
#elif A
class AfterBadElif { }
#region Open
#if !A
class Taken9 { }
#elif true
#endregion
class NotTaken15 { }
#endif
#endregion
#if !A
class Taken10 { }
#if A
class NotTaken16 { }
#if !A
class NotTaken17 { }
#else
class NotTaken18 { }
#endif
#elif !A
class Taken11 { }
#endif
#endif
#define Very_long_identifier_0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789XYZ
#if Very_long_identifier_0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789
class TakenTruncated { }
#endif
#if !A
class Unterminated { }
#if B
class InsideUnterminated { }
