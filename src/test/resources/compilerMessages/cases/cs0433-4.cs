// docs: cs0433.md #4; codes: CS0433
// CS0433_4.cs in CS0433_4.csproj  
// compile with: dotnet build or /reference:cs0433_1.dll /reference:CustomTypes=cs0433_2.dll  
extern alias CustomTypes;  
using TypeBindConflicts;  

public class Test
{  
   public static void Main()
   {  
      // AggPubImpAggPubImp taken from CS0433_2.dll (no alias, default global namespace)
      AggPubImpAggPubImp n6 = new AggPubImpAggPubImp();
      
      // AggPubImpAggPubImp taken from CS0433_1.dll (via CustomTypes alias)
      CustomTypes.TypeBindConflicts.AggPubImpAggPubImp n7 =
          new CustomTypes.TypeBindConflicts.AggPubImpAggPubImp();
   }  
}  
