// docs: warning-waves.md #8; codes: CS7023 CS8073 CS8611 CS8626 CS8826 CS8848 CS8880 CS8881 CS8882 CS8883 CS8884 CS8885 CS8886 CS8887 CS8892 CS8897 CS8898 CS8981 CS9123 CS9265
    class Program
    {
        public static void M(S s)
        {
            if (s == null) { } // CS8073: The result of the expression is always 'false'
            if (s != null) { } // CS8073: The result of the expression is always 'true'
        }
    }

    struct S
    {
        public static bool operator ==(S s1, S s2) => s1.Equals(s2);
        public static bool operator !=(S s1, S s2) => !s1.Equals(s2);
        public override bool Equals(object? other)
        {
            // Implementation elided
            return false;
        }
        public override int GetHashCode() => 0;

        // Other details elided...
    }
