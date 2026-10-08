// docs: cs0433.md #3; codes: CS0433
// CS0433_3.cs in CS0433_3.csproj  
// compile with: dotnet build or /reference:cs0433_1.dll /reference:cs0433_2.dll  
using TypeBindConflicts;

public class Test
{  
   public static void Main()
   {  
      AggPubImpAggPubImp n6 = new AggPubImpAggPubImp();   // CS0433  
   }  
}  
