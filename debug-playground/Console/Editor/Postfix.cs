using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace Playground.Editor;

/// <summary>
/// Live check of the postfix templates by the type of the expression and Rider's templates the plugin lacked (0.1.89), and of the live
/// templates up to Rider's set. Type what a marker says on the empty line under it, check EXPECT, then undo (Ctrl+Z). A postfix list opens
/// after the dot by itself (or Ctrl+Space); Tab or Enter expands the chosen template. A name in a box is a template stop: Tab goes on.
/// </summary>
public class PostfixScenarios
{
    private readonly List<PostfixOrder> _orders = new();

    public PostfixScenarios()
    {

    }

    // TYPE:postfix-by-type — type `ready.` and look at the list: EXPECT `if`, `else`, `while`, `not`, `var` (no `return`: void); NOT `foreach`, `for`,
    // `await`, `null`, `notnull`, `lock`, `using`. Then `orders.` — `foreach`, `for`, `forr`, `notnull`, NOT `if` / `await`; `task.` — `await`,
    // NOT `foreach`; `count.` — `for`, NOT `null` / `foreach`; `name.` — `parse`, `tryparse`, `foreach`. An unknown name (`missing.`) offers all.
    public void ByType(bool ready, int count, string name, List<PostfixOrder> orders, Task<int> task, IEnumerable<int> sequence)
    {

    }

    // TYPE:postfix-whole-condition — on the empty line in WholeCondition type `name.Length > 0.if` + Tab. EXPECT: `if (name.Length > 0)`
    // with a block, the caret inside — NOT `name.Length > 0.if ()` (0.1.168, Jellyfin № 3). Then in Nothing (`void`) type `name.Trim().`:
    // the list has no `return` (the method returns nothing, 0.1.168, № 11). Ctrl+Z.
    public void WholeCondition(string name)
    {

    }

    public void Nothing(string name)
    {

    }

    // TYPE:postfix-loops — type `orders.for` + Tab. EXPECT: `for (var i = 0; i < orders.Count; i++)`; `numbers.forr` → `for (var i =
    // numbers.Length - 1; i >= 0; i--)`; `orders.foreach` → `foreach (var order in orders)` with `order` in a box (Tab — into the body);
    // `sequence.for` is NOT offered (no Count).
    public void Loops(List<PostfixOrder> orders, int[] numbers, IEnumerable<int> sequence)
    {

    }

    // TYPE:postfix-var-names — type `order.Total.var` + Tab. EXPECT: `var orderTotal = order.Total;` with `orderTotal` in a box, the list
    // offers `total` too. `GetOrders().var` → `var orders = GetOrders();`; `new StringReader("x").using` → `using var reader = …`.
    public void Names(PostfixOrder order)
    {

    }

    private List<PostfixOrder> GetOrders() => _orders;

    // TYPE:postfix-field-prop — in the constructor above type `DateTime.Now.field` + Tab. EXPECT: `_now = DateTime.Now;` and a field
    // `private readonly DateTime _now;` after `_orders`. Ctrl+Z, then `DateTime.Now.prop` → `Now = DateTime.Now;` and a property
    // `public DateTime Now { get; }` (get-only: assigned in the constructor). In Names, `order.Total.prop` gives `{ get; set; }`.

    // TYPE:postfix-inject — on the empty line in PostfixService type `IPostfixClock.inject` + Tab. EXPECT: the class header becomes
    // `public class PostfixService(IPostfixClock postfixClock)`, the typed line is empty. In PostfixRepository (it has a constructor) the same
    // gives a parameter `IPostfixClock postfixClock`, a field `private readonly IPostfixClock _postfixClock;` and `_postfixClock = postfixClock;`
    // in the constructor. `.if` and the other statement templates are NOT offered between members.

    // TYPE:postfix-to-arg-sel — in ToArgSel: `name.to` + Tab → `| = name;` with completion at the caret; `name.arg` → `|(name)` (type the
    // method's name); `name.sel` selects `name`; `name.parse` → `int.Parse(name)` with a list of types at `int` (long, double, decimal,
    // DateTime, Guid…); `if (name.tryparse)` → `if (int.TryParse(name, out var value))`.
    public void ToArgSel(string name)
    {

    }

    // TYPE:live-templates — at the empty line in Templates type each and press Tab: `itli` → `for (int i = 0; i < list.Count; i++)` with
    // `var item = list[i];` (type `orders` at `list` — the element becomes `order`); `nguid` → a new GUID; `unchecked` / `checked` /
    // `unsafe` → a block; `#if` → `#if DEBUG … #endif`; `sfc`, `outv`, `asrt`. In the lookup each template shows Rider's description
    // (`itli` — "Iterate a IList<T>"). Between members of PostfixCtor: `ctorf` → `public PostfixCtor(int id, string name)` assigning both
    // fields; `ctorp` → a constructor of `Total`; `equals`, `indexer`, `iterator`, `propdp`.
    public void Templates()
    {

    }
}

public class PostfixOrder
{
    public decimal Total { get; set; }
}

public interface IPostfixClock
{
    DateTime Now { get; }
}

public class PostfixService
{

}

public class PostfixRepository
{
    private readonly string _name;

    public PostfixRepository(string name)
    {
        _name = name;
    }

}

public class PostfixCtor
{
    private readonly int _id;
    private string? name;

    public decimal Total { get; set; }

    public override string ToString() => $"{_id} {name} {Total}";

    // TYPE:postfix-foreach-char — on the empty line in Letters type `word.ToCharArray().foreach` + Tab. EXPECT: `foreach (var c in
    // word.ToCharArray())` with `var` selected first (Tab offers `char` too), then the name `c` — not `item` (0.1.169, Jellyfin № 7)
    public void Letters(string word)
    {
        Console.WriteLine(word);

    }

    // TYPE:ctor-blank-line — under the last field of PostfixSpacing type `ctor` + Tab. EXPECT: an empty line between the field and
    // `public PostfixSpacing()` (as Rider); typed right under `{` no empty line (0.1.169, Jellyfin № 10)
    public class PostfixSpacing
    {
        private readonly int _count = 0;

        public override string ToString() => _count.ToString();
    }
}
