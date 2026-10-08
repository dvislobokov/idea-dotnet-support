// docs: dynamic-type-and-binding-errors.md #1; codes: CS1962 CS1964 CS1965 CS1966 CS1967 CS1968 CS1969 CS1970 CS1971 CS1972 CS1973 CS1974 CS1975 CS1976 CS1977 CS1978 CS1979 CS1980 CS1981 CS7083 CS8133 CS8364 CS8416 CS9230
dynamic d = GetObject();

// Avoid:
d.ConditionalMethod(); // CS1974 - can fail at runtime

// Recommended:
MyClass obj = (MyClass)d;
obj.ConditionalMethod(); // Compile-time checks ensure correctness
