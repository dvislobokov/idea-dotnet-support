using Shop.Api.Domain;

namespace Shop.Api.Playground;

// Gray text while typing (0.1.101), rule-based, no ML: the type named as the variable, `;` past `)`, the members of an empty initializer
// with their values, the value of `Name = `, the name after a type, the file's name after `class `.
// Type on the empty line under a marker (or where the marker says), look at the gray text, Tab takes it; undo with Ctrl+Z.
// With the completion list open (0.1.102) the gray text follows the selected row: Tab takes the row and the gray text and closes the
// list, Enter takes the row alone as before. The row `Member { … }` under `Member` writes the initializer with the values at hand.
// The file compiles as it is.

public class Member
{
    public string Name { get; set; } = "";
    public string Email { get; set; } = "";
    public int Age { get; set; }
    public bool Admin { get; set; }
}

public class MemberDto
{
    public string Name { get; set; } = "";
    public string Email { get; set; } = "";
}

public static class GhostSuggestionsTour
{
    public static Member Create(MemberDto dto, int age, string name)
    {
        // TYPE:ghost-list-new — type `var member = new ` and let the list open by itself (0.1.102).
        // EXPECT: the row `Member` is selected and the gray text `Member();` stands after `new ` while the list is open. Arrow Down to
        //   `MemberDto`: the gray text becomes `MemberDto();`. Tab: `var member = new MemberDto();`, the list is closed, the caret after `;`.
        //   Enter instead of Tab: the row alone, as before (`new MemberDto()` with the caret between the parentheses).
        //   Typed on: `var member = new Mem` with `Member` selected → gray `ber();` after `Mem`.
        // EXPECT (not): gray text on the row `Member { … }`, on `Shipment` (it wants a carrier), on `Product` (required members), on a row
        //   that does not begin with what is typed.
        var member = new Member(){

        };
        var isAdmin = true;
        member.Name = name;
        member.Admin = isAdmin;

        // TYPE:new-initializer-row — type `var member = new Membe` (0.1.102).
        // EXPECT: the list has `Member` and right under it `Member { … }` (gray `initializer` at the right); `Member` stays first.
        //   Enter on `Member { … }` writes `new Member`, `{` on the next line and a member a line: `Name = name,` / `Email = dto.Email,` /
        //   `Age = age,` / `Admin = ` and `}`, as a template: `name` is selected, Tab goes to `dto.Email`, `age`, the empty `Admin = `, then
        //   past the `}`. Typing over a selected value replaces it.
        //   `var product = new Produ` → the row `Product` (it has required members) writes `Sku = |,` / `Title = ` the same way, one
        //   `Product` row, no `Product { … }`.
        // EXPECT (not): `Shipment { … }` after `var shipment = new Shipm` (no constructor without arguments); a `{ … }` row after `new M`
        //   (one letter: not for every type of the list) — but `var member = new ` has it, the type the variable names.
        // TYPE:ghost-new-by-name — type `var member = new ` and press Esc if the list opened.
        // EXPECT: gray `Member();` after `new `; Tab writes `var member = new Member();`. The list (Ctrl+Space) has `Member` first.
        //   `var members = new ` → gray `List<Member>();`. `var mem = new Me` → nothing gray.
        // EXPECT (not): gray text after `var shipment = new ` (Shipment wants a carrier) or after `var product = new ` (Product has required members).
        var members = new List<Member>();
        // TYPE:ghost-close-call — type `var member = new Member(` (the `)` comes by itself, the caret stays between the parentheses).
        // EXPECT: a gray `;` after the `)`; Tab writes `var member = new Member();` with the caret after `;` — one `)`, not two.
        // EXPECT (not): a gray `;` in `var shipment = new Shipment(|)` (its constructor wants an argument).

        // TYPE:ghost-fill — type `var member = new Member()`, Enter, `{`, Enter (the `}` comes by itself, the caret on the empty line).
        // EXPECT: gray lines `Name = name,` / `Email = dto.Email,` / `Age = age,` / `Admin = ` (the parameter `name` wins over `dto.Name`:
        //   the same name and the shorter path). Tab writes them all; a gray `;` follows the `}`. The empty `Admin = ` is yours to fill.

        // TYPE:ghost-member-value — type `var member = new Member { Name = ` (the `}` comes by itself).
        // EXPECT: gray `name`. With `Name = dt` the gray text is `o.Name`; `Email = ` gives `dto.Email`; `Age = ` gives `age`.
        // EXPECT (not): `name;` — no `;` inside an initializer; nothing gray after `Admin = ` (no bool at hand).

        return new Member { Name = name, Email = dto.Email, Age = age };
    }

    // TYPE:ghost-parameter-name — put the caret between the parentheses of `Draft()` below and type `MemberDto ` (with the space).
    // EXPECT: gray `memberDto`; Tab writes `Draft(MemberDto memberDto)`. After `, IMemberService ` the gray text is `memberService`,
    //   after `, List<Member> ` it is `members`. On the empty line in the body: `Member ` → `member`, `foreach (var ` with ` in members)`
    //   after the caret → `member`.
    // EXPECT (not): a gray name after `string ` or `int ` (no name of a type to make).
    // TYPE:ghost-list-name — between the parentheses of `Draft()` type `Mem` and press Ctrl+Space (0.1.102).
    // EXPECT: with `MemberDto` selected the gray text is `berDto memberDto`; Tab writes `Draft(MemberDto memberDto)`. With `Member`
    //   selected: `ber member`. Enter takes the row alone.
    public static void Draft()
    {

    }

    // TYPE:ghost-field-name — on the empty line below type `private static readonly MemberDto `.
    // EXPECT: gray `_memberDto`. `public static MemberDto ` gives `MemberDto` and, once taken, ` { get; set; }` follows in gray.

    // TYPE:ghost-class-once — on the empty line below type `public clas`.
    // EXPECT: the list has one `class` row (the keyword), not two (the keyword and the live template). Esc, undo.

    // TYPE:ghost-file-name — below the last `}` of this file type `public class ` and press Ctrl+Space.
    // EXPECT: a bold row `GhostSuggestions  file name`; Enter writes `public class GhostSuggestions`, `{`, an empty line, `}`, with the name
    //   selected as in a template: Enter keeps it, typing replaces it. In a file that has a type of its name already the row is not there.
}
