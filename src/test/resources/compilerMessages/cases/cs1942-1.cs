// docs: cs1942.md #1; codes: CS1942 CS1949
// cs1942.cs  
class Program  
    {  
        static void Main(string[] args)  
        {  
            var x = from var i in Enumerable.Range(1, 100) // CS1949  
                    select i; //CS1942  
        }  
    }  
