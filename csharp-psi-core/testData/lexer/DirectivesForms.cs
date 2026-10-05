#pragma warning disable CS0168, x // comment
#pragma warning restore
#pragma warning disable
#pragma warning foo
#pragma foo bar
#pragma checksum "file.cs" "{406EA660-64CF-4C82-B6F0-42D48172A799}" "ab12"
#nullable enable
#nullable disable warnings
#nullable restore annotations
#nullable foo
#line 100 "file.cs"
#line default
#line hidden
#line 1"f"
#line (1, 1) - (2, 10) 5 "file.cs"
#line (1,1)-(2,2) "f"
#line 1 """raw"""
#line 12a 1.5
#region Some text // not a comment
#region// a comment
#region
#endregion trailing text
#endregion
#endregion
#error Something: "x" A /* c */
#warning   spaced
#error
#r "lib.dll"
#load "x.csx"
#!/usr/bin/env dotnet
#:package Foo@1.0
#: x
# :x
#foo bar
#
#123
#!= x
# region spaced
#endregion
  #   pragma   warning   disable   CS1
#if A + B & C | D
#endif
#if A /* c */ 'x' @B "s" A 1 \x ` $ ;
#endif
#if if || if || if || define
#endif
#define if
#if if
class Taken1 { }
#endif
#pragma warning disable AB, 1
#if A
class NotTaken { }
#  else
class Taken2 { }
#	endif
