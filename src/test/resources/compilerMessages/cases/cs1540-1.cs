// docs: cs1540.md #1; codes: CS1540
using System;  
using System.Text;  
  
namespace CS1540  
{  
    class Program1  
    {  
        static void Main()  
        {  
            Employee.PreparePayroll();  
        }  
    }  
  
    class Person  
    {  
        protected virtual void CalculatePay()
        {  
            Console.WriteLine("CalculatePay in Person class.");  
        }  
    }  
  
    class Manager : Person  
    {  
        protected override void CalculatePay()
        {  
            Console.WriteLine("CalculatePay in Manager class.");
  
        }  
    }  
  
    class Employee : Person  
    {  
        public static void PreparePayroll()  
        {  
            Employee emp1 = new Employee();  
            Person emp2 = new Manager();  
            Person emp3 = new Employee();  
            // The following line calls the method in the Employee base class,  
            // Person.  
            emp1.CalculatePay();
  
            // The following lines cause compiler error CS1540. The compiler
            // cannot determine at compile time what the run-time types of
            // emp2 and emp3 will be.  
            //emp2.CalculatePay();
            //emp3.CalculatePay();  
  
        }  
    }  
}  
