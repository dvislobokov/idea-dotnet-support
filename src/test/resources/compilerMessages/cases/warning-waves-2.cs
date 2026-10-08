// docs: warning-waves.md #2; codes: CS7023 CS8073 CS8611 CS8626 CS8826 CS8848 CS8880 CS8881 CS8882 CS8883 CS8884 CS8885 CS8886 CS8887 CS8892 CS8897 CS8898 CS8981 CS9123 CS9265
    ref struct Container
    {
        // CS9265: Field 'value' is never ref-assigned to,
        // and will always have its default value (null reference)
        public ref int value;
    }
