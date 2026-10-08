namespace Playground.Editor;

/// <summary>
/// Live check of the mapping completion (0.1.134, ROADMAP: «Mapping completion»).
/// Lines to type on are marked <c>// TYPE:name</c>: put the caret on the empty line under the marker, type what the comment says,
/// compare the list with EXPECT. Nothing here is called, the file only has to compile: undo what was typed (Ctrl+Z) before the next marker.
/// </summary>
public class MappingUserProfile
{
    public string Email { get; set; } = "";
    public string City { get; set; } = "";
}

public class MappingUser
{
    public int Id { get; set; }
    public string Name { get; set; } = "";
    public string Login { get; set; } = "";
    public DateTime Created { get; set; }
    public MappingUserProfile Profile { get; set; } = new();
}

public class MappingUserDto
{
    public int Id { get; set; }
    public string Name { get; set; } = "";
    public string Email { get; set; } = "";
    public int Created { get; set; }
    public string Note { get; init; } = "";
    public bool Active { get; set; }
}

public class Mapping
{
    public MappingUserDto ToDto(MappingUser user, string note)
    {
        var dto = new MappingUserDto();
        dto.Id = user.Id;

        // TYPE:map-statement — Ctrl+Space on the empty line above (under `dto.Id = user.Id;`). EXPECT: first `dto.Name = user.Name;`,
        // `dto.Email = user.Profile.Email;` (grey `map`, `from user`) and «Map all remaining members from user» (bold); no `dto.Note` (init only),
        // no `dto.Id` (set), no `dto.Created` (DateTime → int). Enter on the map-all row writes both lines at this indentation

        return new MappingUserDto
        {
            Id = user.Id,
            // TYPE:map-initializer — Ctrl+Space (or type `Na`). EXPECT: `Name = user.Name`, `Email = user.Profile.Email`, `Note = note` first,
            // then the plain members; Enter on `Name = user.Name` writes it with a trailing comma; «Map all remaining members from user» writes
            // Name and Email (not Note: another object; not Active: nothing at hand)
        };
    }

    public MappingUserDto Empty(MappingUser user)
    {
        // TYPE:map-empty — `var d = new MappingUserDto { ` and Ctrl+Space. EXPECT: the mapping rows first (user fills Id, Name, Email);
        // with `Active = true, ` typed before the caret they come after the members (no partner yet)

        // TYPE:map-off — Settings | .NET → Behavior → «Offer to copy members…» off, then `var d = new MappingUserDto { ` + Ctrl+Space. EXPECT: no `… = user.…` rows

        return new MappingUserDto { Id = user.Id };
    }
}
