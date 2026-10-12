namespace Playground.Editor;

/// <summary>
/// Live check of Rider's hierarchy refactorings in Refactor This (Ctrl+Alt+Shift+T): Pull Members Up, Push Members Down, Use Base Type
/// Where Possible (Jellyfin № 53, № 55). Put the caret where a marker says, open Refactor This, pick the row, answer the dialog, compare
/// with EXPECT, undo (Ctrl+Z). Nothing here is called, the file only has to compile.
/// </summary>
public class HierAnimal
{
    public string Name { get; set; } = "";
}

// TYPE:hier-pull-up — caret on `Double` below, Refactor This → «Pull Members Up», target `HierAnimal`, members `Double` and `Tricks`.
// EXPECT: both members move to the end of HierAnimal; `Double` becomes `protected` there (Score still calls it); HierDog keeps Score only.
// TYPE:hier-pull-conflict — same, but tick `Purr` too. EXPECT: a "Conflicts" question: «'Purr' uses '_purrs', which stays in 'HierDog'».
public class HierDog : HierAnimal
{
    private int _purrs;

    public List<string> Tricks { get; } = new List<string>();

    private int Double(int x) => x * 2;

    public int Score() => Double(3);

    public void Purr() => _purrs++;
}

// TYPE:hier-pull-abstract — caret on `Area` in HierCircle, «Pull Members Up» to HierShape, «Make abstract» ticked. EXPECT: HierShape becomes
// `public abstract class` with `public abstract double Area();`, HierCircle's `virtual` turns into `override`.
public class HierShape
{
}

public class HierCircle : HierShape
{
    public virtual double Area() => 3.14;
}

// TYPE:hier-pull-interface — caret on the header of HierJob, «Pull Members Up», target IHierRunner, member `Run`. EXPECT: `void Run(int times);`
// in IHierRunner, HierJob unchanged.
public interface IHierRunner
{
}

public class HierJob : IHierRunner
{
    public void Run(int times)
    {
    }
}

// TYPE:hier-push-down — caret on `Wheels` in HierVehicle, Refactor This → «Push Members Down». EXPECT: `Wheels` leaves HierVehicle and appears at the
// end of HierCar and HierBike. Tick `Start` too: EXPECT a conflict «'HierVehicle' uses 'Start', which goes to the derived types» (Go calls it).
// With «Keep abstract declaration» on `Wheels` (made `virtual` first): HierVehicle abstract with `public abstract int Wheels();`, copies `override`.
public class HierVehicle
{
    public int Wheels() => 4;

    public void Start()
    {
    }

    public void Go() => Start();
}

public class HierCar : HierVehicle
{
    public string Model = "";
}

public class HierBike : HierVehicle
{
}

// TYPE:hier-use-base — caret on the header of HierSquare, Refactor This → «Use Base Type Where Possible», IHierShape. EXPECT: the confirmation lists
// the parameters of `Total` and `Missing`; after it they are `IHierShape s`; `Edge(HierSquare s)` keeps its type (`Side` is not in IHierShape).
public interface IHierShape
{
    double Area();
}

public class HierSquare : IHierShape
{
    public double Area() => 1;

    public double Side => 1;
}

public class HierReport
{
    public double Total(HierSquare s) => s.Area();

    public double Edge(HierSquare s) => s.Side;

    public bool Missing(HierSquare s) => s == null;
}
