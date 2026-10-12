namespace Playground.Editor;

/// <summary>
/// Live check of double completion (0.1.96): the types of packages other projects of the solution reference and this one does not.
/// Console references Microsoft.Extensions.Hosting and Lib; Grpc references Grpc.AspNetCore, Web and Worker nothing more. Restore and the
/// indexing of the assemblies must have run (the indexes of every project). Type on the empty line under the marker, compare with EXPECT, Ctrl+Z.
/// </summary>
public static class DoubleCompletion
{
    public static void Run()
    {
        // TYPE:double-package — type `GrpcServi` then Ctrl+Space once, look, then Ctrl+Space again.
        // EXPECT: first press: nothing of gRPC; the advertisement line says "Press Ctrl+Space again to show types of packages the project does not reference".
        // EXPECT: second press: `GrpcServiceOptions (in Grpc.AspNetCore.Server, Grpc.AspNetCore.Server 2.83.0)` (the package that has the dll).
        //   Enter writes `GrpcServiceOptions`, adds `using Grpc.AspNetCore.Server;` at the top and shows a balloon "`GrpcServiceOptions` is in
        //   Grpc.AspNetCore.Server 2.83.0" with the button "Add package Grpc.AspNetCore.Server 2.83.0"; the button runs `dotnet add package`.
        //   Nothing runs without the button.

        // TYPE:double-nothing — press Ctrl+Space twice on the empty line (nothing typed).
        // EXPECT: no rows "(in …, … 2.83.0)": what is not referenced waits for the first letter.
    }
}
