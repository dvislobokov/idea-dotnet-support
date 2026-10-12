// An open #region in an excluded branch makes #elif, #else and #endif bad directives (ERR_EndRegionDirectiveExpected):
// the branch stays excluded until #endregion, then #endif closes the #if.
#if false
#region Inside
class NotTaken1 { }
#elif true
class NotTaken2 { }
#endif
class NotTaken3 { }
#endregion
class NotTaken4 { }
#else
class NotTaken5 { }
#endif
class Active { }
#region Outer
#if true
class Taken1 { }
#endregion
class Taken2 { }
#endif
#endregion
#endif
#else
#elif X
#endregion
