// docs: warning-waves.md #10; codes: CS7023 CS8073 CS8611 CS8626 CS8826 CS8848 CS8880 CS8881 CS8882 CS8883 CS8884 CS8885 CS8886 CS8887 CS8892 CS8897 CS8898 CS8981 CS9123 CS9265
    public struct DefiniteAssignmentWarnings
    {
        // CS8880
        public Struct Property { get; }
        // CS8881
        private Struct field;

        // CS8882
        public void Method(out Struct s)
        {

        }

        public DefiniteAssignmentWarnings(int dummy)
        {
            // CS8883
            Struct v2 = Property;
            // CS8884
            Struct v3 = field;
            // CS8885:
            DefiniteAssignmentWarnings p2 = this;
        }

        public static void Method2(out Struct s1)
        {
            // CS8886
            var s2 = s1;
            s1 = default;
        }

        public static void UseLocalStruct()
        {
            Struct r1;
            var r2 = r1;
        }
    }
