using Shop.Api.Domain;

namespace Shop.Api.Playground;

// Gray text while typing (0.1.101), rule-based, no ML: the type named as the variable, `;` past `)`, the members of an empty initializer
// with their values, the value of `Name = `, the name after a type, the file's name after `class `.
// Type on the empty line under a marker (or where the marker says), look at the gray text, Tab takes it; undo with Ctrl+Z.
// Gray text does not show while the completion list is open: press Esc first where the list opens by itself (after `new `).
// The file compiles as it is.

public interface IMemberService
{
    Member Find(string name);
}
