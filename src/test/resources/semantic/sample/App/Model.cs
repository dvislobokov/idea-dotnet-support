namespace App.Model
{
    public class Order
    {
        public decimal Price { get; set; }
    }
}

namespace App
{
    public class Base
    {
        protected string Name = "orders";

        protected void Log(string text) => System.Console.WriteLine(text);
    }
}
