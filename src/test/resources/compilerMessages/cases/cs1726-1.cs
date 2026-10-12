// docs: cs1726.md #1; codes: CS1726
// Save this code as CS1726.cs  
  
// Run the following command to create CS1726.key:  
//      sn -k CS1726.key  
  
// Then compile by using the following command:
//      csc /keyfile:CS1726.key /target:library CS1726.cs  
  
using System.Runtime.CompilerServices;  
  
// The following line causes compiler error CS1726.  
[assembly: InternalsVisibleTo("UnsignedAssembly")]
  
// To get rid of the error, try the following line instead.  
//[assembly: InternalsVisibleTo("SignedAssembly, PublicKey=0024000004800000940000000602000000240000525341310004000001000100031d7b6f3abc16c7de526fd67ec2926fe68ed2f9901afbc5f1b6b428bf6cd9086021a0b38b76bc340dc6ab27b65e4a593fa0e60689ac98dd71a12248ca025751d135df7b98c5f9d09172f7b62dabdd302b2a1ae688731ff3fc7a6ab9e8cf39fb73c60667e1b071ef7da5838dc009ae0119a9cbff2c581fc0f2d966b77114b2c4")]  
  
class A { }  
