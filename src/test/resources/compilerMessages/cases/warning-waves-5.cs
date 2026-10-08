// docs: warning-waves.md #5; codes: CS7023 CS8073 CS8611 CS8626 CS8826 CS8848 CS8880 CS8881 CS8882 CS8883 CS8884 CS8885 CS8886 CS8887 CS8892 CS8897 CS8898 CS8981 CS9123 CS9265
    public partial class PartialType
    {
        public partial void M1(int x);

        public partial T M2<T>(string s) where T : struct;

        public partial void M3(string s);


        public partial void M4(object o);
        public partial void M5(dynamic o);
        public partial void M6(string? s);
    }
