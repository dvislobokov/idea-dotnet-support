// docs: warning-waves.md #6; codes: CS7023 CS8073 CS8611 CS8626 CS8826 CS8848 CS8880 CS8881 CS8882 CS8883 CS8884 CS8885 CS8886 CS8887 CS8892 CS8897 CS8898 CS8981 CS9123 CS9265
    public partial class PartialType
    {
        // Different parameter names:
        public partial void M1(int y) { }

        // Different type parameter names:
        public partial TResult M2<TResult>(string s) where TResult : struct => default;

        // Relaxed nullability
        public partial void M3(string? s) { }


        // Mixing object and dynamic
        public partial void M4(dynamic o) { }

        // Mixing object and dynamic
        public partial void M5(object o) { }

        // Note: This generates CS8611 (nullability mismatch) not CS8826
        public partial void M6(string s) { }
    }
