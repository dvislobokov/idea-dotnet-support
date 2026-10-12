using System;

namespace Playground.Editor;

/// <summary>
/// Live check of the Alt+Enter context actions on <c>switch</c> (0.1.162, <c>NativeCSharpSwitchActions.kt</c>): the missing sections and
/// arms of an enum, <c>default</c>, section braces, statement ↔ expression, statement → <c>if</c> chain. «Context actions» = Built-in (the
/// default). Put the caret where a marker says, press Alt+Enter, check EXPECT, then undo (Ctrl+Z) so the file stays as it is. Nothing here
/// is called, the file only has to compile.
/// </summary>
public class SwitchActions
{
    public enum OperationType { Insert, Update, Delete, Read }

    // TYPE:switch-add-sections — Alt+Enter on `switch` of Sections: "Add 'switch' sections". EXPECT: `case OperationType.Delete:` /
    // `break;`, `case OperationType.Read:` / `break;` and `default:` / `break;` added before the closing brace, in this order, with the
    // indent of the sections. NOT offered on `switch` of Full (every member and default are there) nor on `switch (count)` of Count (not an enum).
    public static void Sections(OperationType operationType)
    {
        switch (operationType)
        {
            case OperationType.Insert:
                break;
            case OperationType.Update:
                break;
        }
    }

    // TYPE:switch-add-default — Alt+Enter on `switch` of Count: "Add 'default' section". EXPECT: `default:` / `break;` after `case 1:`.
    // NOT offered on `switch` of Full.
    public static void Count(int count)
    {
        switch (count)
        {
            case 1:
                break;
        }
    }

    public static string Full(OperationType operationType)
    {
        switch (operationType)
        {
            case OperationType.Insert: return "i";
            case OperationType.Update: return "u";
            case OperationType.Delete: return "d";
            case OperationType.Read: return "r";
            default: return "?";
        }
    }

    // TYPE:switch-add-braces — Alt+Enter on `switch` of Braces: "Add section braces". EXPECT: the statements of `case OperationType.Insert:`
    // and of `default:` inside `{ }` at the indent of the label; the braced `case OperationType.Update:` section stays as it is.
    // NOT offered once every section is braced.
    public static string Braces(OperationType operationType)
    {
        switch (operationType)
        {
            case OperationType.Insert:
                return "Insert";
            case OperationType.Update:
            {
                return "Update";
            }
            default: return "?";
        }
    }

    // TYPE:switch-to-expression — Alt+Enter on `switch` of Name: "To 'switch' expression". EXPECT: `return operationType switch` with an
    // arm per section (`OperationType.Insert => "Insert",` …) and `_ => "?"` for `default`, `};` at the end. On `switch` of Assign (no
    // `default`, the sections assign `name` and one throws): `name = operationType switch { …, OperationType.Delete => throw new
    // InvalidOperationException(), _ => throw new ArgumentOutOfRangeException(nameof(operationType), operationType, null) };`.
    // NOT offered on `switch` of Mixed (two labels of one section, a section of two statements).
    public static string Name(OperationType operationType)
    {
        switch (operationType)
        {
            case OperationType.Insert:
                return "Insert";
            case OperationType.Update:
                return "Update";
            default:
                return "?";
        }
    }

    public static void Assign(OperationType operationType)
    {
        string name;
        switch (operationType)
        {
            case OperationType.Insert:
                name = "Insert";
                break;
            case OperationType.Update:
                name = "Update";
                break;
            case OperationType.Delete:
                throw new InvalidOperationException();
        }
    }

    // TYPE:switch-to-if — Alt+Enter on `switch` of Mixed: "To 'if' statements". EXPECT: `if (operationType == OperationType.Insert ||
    // operationType == OperationType.Update) { Console.WriteLine("write"); return "iu"; } else if (operationType == OperationType.Read && (depth > 0))
    // { return "r"; } else { return "?"; }`, each branch braced on its own lines, the `break;` of a section dropped. NOT offered on `switch`
    // of Jump (`goto case`).
    public static string Mixed(OperationType operationType, int depth)
    {
        switch (operationType)
        {
            case OperationType.Insert:
            case OperationType.Update:
                Console.WriteLine("write");
                return "iu";
            case OperationType.Read when depth > 0:
                return "r";
            default:
                return "?";
        }
    }

    public static void Jump(int count)
    {
        switch (count)
        {
            case 1:
                goto case 2;
            case 2:
                break;
        }
    }

    // TYPE:switch-add-arms — Alt+Enter on `switch` of Arms: "Add missing arms". EXPECT: `OperationType.Delete => throw new
    // NotImplementedException(),` and `OperationType.Read => throw new NotImplementedException(),` before the `_` arm. On `switch` of
    // Short (no `_` arm): the missing arms after the last one and `_ => throw new ArgumentOutOfRangeException()` last.
    public static string Arms(OperationType operationType)
    {
        return operationType switch
        {
            OperationType.Insert => "Insert",
            OperationType.Update => "Update",
            _ => "?",
        };
    }

    public static string Short(OperationType operationType)
    {
        return operationType switch
        {
            OperationType.Insert => "Insert"
        };
    }

    // TYPE:switch-to-statement — Alt+Enter on `switch` of Statement: "To 'switch' statement". EXPECT: `switch (operationType)` with
    // `case OperationType.Insert:` / `return "Insert";`, …, `default:` / `throw new ArgumentOutOfRangeException();`. On `switch` of
    // Assigned: `case 1:` / `name = "one";` / `break;` … `default:` / `name = "?";` / `break;`. NOT offered on the `switch` inside the
    // argument of Console.WriteLine in Argument.
    public static string Statement(OperationType operationType)
    {
        return operationType switch
        {
            OperationType.Insert => "Insert",
            OperationType.Update => "Update",
            _ => throw new ArgumentOutOfRangeException(),
        };
    }

    public static void Assigned(int count)
    {
        string name;
        name = count switch { 1 => "one", 2 => "two", _ => "?" };
        Console.WriteLine(name);
    }

    public static void Argument(int count)
    {
        Console.WriteLine(count switch { 1 => "one", _ => "?" });
    }
}
