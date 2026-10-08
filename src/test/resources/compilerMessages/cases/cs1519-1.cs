// docs: cs1519.md #1; codes: CS1519
// CS1519.cs  
// Generates CS1519 because a class name cannot be a number:  
class Test 42
{  
// Generates CS1519 because of 'j' following 'I'  
// with no comma between them:  
    int i j;
// Generates CS1519 because of "checked" on void method:  
    checked void f4();
  
// Generates CS1519 because of "num":  
    void f5(int a num){}
  
// Generates CS1519 because of namespace inside class:  
    namespace;
  
}  
