// A header comment stays where it is.
#nullable enable
using System;

namespace Demo.Formatting;
public enum Color{Red,Green,
Blue}
    #region Members
/// <summary>
  /// A doc comment moves with its member.
  /// </summary>
  public interface IShape{double Area();}
#endregion
public record Point(int X,int Y);
public struct Size{public int W;public int H;}
public static class Switches{
public static string Name(Color c){
switch(c){
case Color.Red:
return "red";
case Color.Green:{
return "green";
}
default:
  // the default
break;
}
#if DEBUG
        Console.WriteLine( "debug" );
#else
        Console.WriteLine(  "release"  );
#endif
    /* a block comment */
label:
var verbatim=@"line one
    line two keeps its spaces";
string text="a  b";   // a trailing comment
return verbatim+text;
}
public static int Generic<T>(T value)where T:class,new(){
return value==null?0:1;
}
public static int[] Ranges(int[] a)=>a[1..^1];
public static bool Patterns(object o)=>o is int{}and not 0 or string;
}
