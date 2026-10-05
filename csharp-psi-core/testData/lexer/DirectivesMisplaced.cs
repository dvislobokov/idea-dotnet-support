// A directive after a token or a comment on its line is misplaced (ERR_BadDirectivePlacement): no effect, and in
// trailing trivia the next lines that start with a directive are misplaced too (the directive takes its new line).
x; #define Y
#define Z
  #if Z
y;
#if Y || Z
class NotTaken1 { }
#endif
/* c */ #if Q
z;
/* c */ /// doc
#if W
class NotTaken2 { }
#endif
/** d */ #if V
class NotTaken3 { }
#endif
w; /** d */ #if U
class NotTaken4 { }
#endif
v; // c
#if T
class NotTaken5 { }
#endif
u; /// doc
#if S
class NotTaken6 { }
#endif
#define
#if !
class NotTaken7 { }
#endif
#undef
#if !
class Taken1 { }
#endif
t; #region R
