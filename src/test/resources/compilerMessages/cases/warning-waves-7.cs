// docs: warning-waves.md #7; codes: CS7023 CS8073 CS8611 CS8626 CS8826 CS8848 CS8880 CS8881 CS8882 CS8883 CS8884 CS8885 CS8886 CS8887 CS8892 CS8897 CS8898 CS8981 CS9123 CS9265
    static class StaticClass
    {
        public static void Thing() { }
    }

    void M(object o)
    {
        // warning: cannot use a static type in 'is' or 'as'
        if (o is StaticClass)
        {
            Console.WriteLine("Can't happen");
        }
        else
        {
            Console.WriteLine("o is not an instance of a static class");
        }
    }
