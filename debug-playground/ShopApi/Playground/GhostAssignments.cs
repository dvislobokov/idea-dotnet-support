namespace Shop.Api.Playground;

// Gray text and list rows at an assignment to a member (0.1.103): `member.Admin = ` gets the value at hand, `member.` with a member
// selected in the list gets ` = value;` after it, and the list at `member.Email = ` has the paths into the values at hand (`dto.Email`).
// Type on the empty line under a marker, look, undo with Ctrl+Z. The file compiles as it is.
public static class GhostAssignments
{
    public static Member Update(MemberDto dto, bool isAdmin, string name)
    {
        // TYPE:ghost-assign-selected — type `member.Ad` and let the list open (or Ctrl+Space).
        // EXPECT: with `Admin` selected the gray text is `min = isAdmin;`; Tab writes `member.Admin = isAdmin;`.
        // EXPECT (not): `min admin` (a name as after a type) — `member.Admin` is a member of a value, not a type.

        // TYPE:ghost-assign-value — type `member.Email = ` (Esc if a list opens).
        // EXPECT: gray `dto.Email;`. `member.Name = ` gives `name;` (the parameter of the same name over `dto.Name`), `member.Admin = `
        //   gives `isAdmin;`. EXPECT (not): `member.Email;` — never the member itself.

        // TYPE:ghost-assign-list — type `member.Email = ` and press Ctrl+Space.
        // EXPECT: the rows `dto.Email` (gray `value` at the right) and `dto.Name` near the top, before the rest of the list; Enter writes the path.

        return null;
    }

    // TYPE:ghost-member-of-type — on the empty line in the body type `System.Text.Json.JsonSerializer.Serialize ` (with the space), then
    //   `Console.Out ` (0.1.104).
    // EXPECT (not): gray `serialize` / `out` — a method or a property of a type is no type to name a variable after.
    //   `Shop.Api.Playground.MemberDto ` still gives gray `memberDto`.
    public static void MemberOfAType()
    {
    }
}
