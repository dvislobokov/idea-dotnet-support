using UnityEngine;

// UNITY_PLAN.md: open the folder debug-playground/Unity (Unity.sln) with the Unity editor 6000.6.0f1 installed by Unity Hub.
//
// UNITY:view — the Solution view. EXPECT: the solution node "Unity" with the Unity icon and "Unity 6000.6.0f1 · 1 project"; under
// Assembly-CSharp → Assets → Scripts only the .cs files, no .meta; the view options (⋮ of the tool window) have "Show .meta Files",
// which brings Player.cs.meta back. .NET menu → "Open in Unity Editor 6000.6.0f1" is there.
//
// UNITY:lenses — EXPECT over the declarations below: "Unity script" over Player, "Set by Unity" over speed and jumpHeight, "no usages"
// over notSerialized, "Event function" over Update and OnTriggerEnter, "Called by Unity" over ResetSpeed, "no usages" over Helper.
//
// UNITY:new — right-click Assets/Scripts → New. EXPECT: "Unity ▸" right after Class/Interface, then C#, Configuration... (no ".NET ▸"
// level); Unity ▸ MonoBehaviour Script, name Enemy → Enemy.cs with `using UnityEngine;`, `public class Enemy : MonoBehaviour`, Start and
// Update, no namespace (no assembly definition, no root namespace, and a Scripts folder is no namespace — as Unity writes it). In
// Assets/Gameplay (with Gameplay.asmdef, rootNamespace Demo.Gameplay) the same gives `namespace Demo.Gameplay` as a block (LangVersion 9).
// Delete the new files afterwards.
//
// UNITY:editor-link (0.1.270) — open this folder in the Unity editor too, then switch back to the IDE. EXPECT: a notification offers the
// editor package; Install puts Packages/io.github.dotnetsupport.unity (not in `git status`, Packages/packages-lock.json gains a line);
// the status bar says "Unity: edit mode", the toolbar has Play / Pause / Step. Play → the editor plays without being focused, the status
// bar says "playing"; Pause, Step, Play again (Stop). Delete the `;` after `speed = 5f` below and Ctrl+S: Unity compiles at once, the status
// bar says "compiling", then "1 compile error"; Ctrl+Z, Ctrl+S → "edit mode". Shift+F6 on Player.cs in the Solution view: the new name is
// in the tree at once, Player.cs.meta goes with it (rename back). The NuGet window says "NuGet is not available for Unity projects".
//
// UNITY:editor-log (0.1.271) — with the editor connected, open the window "Unity" (the stripe, or .NET → Show Unity Log). EXPECT: Play,
// walk into a trigger: "Debug.Log(other.name)" below gives a line with the info icon and the time; selected, the details show the stack
// with `Assets/Scripts/Player.cs:44` as a link; Enter or a double click opens Player.cs on that line. Play again with "Clear on Play" on:
// the old lines are gone. Delete the `;` after `speed = 5f` and Ctrl+S: two red lines "Assets/Scripts/Player.cs(…): error CS1002: ;
// expected" (no copies), Enter opens the place; Ctrl+Z, Ctrl+S: they are gone. Clear keeps the errors of a compilation; the filters
// (errors / warnings / messages) say their counts in their tooltips; Collapse makes identical lines one with "×N".
public class Player : MonoBehaviour
{
    [SerializeField] private float speed = 5f;
    public float jumpHeight = 2f;
    private int notSerialized;

    void Update()
    {
        transform.position += Vector3.forward * (speed * Time.deltaTime);
    }

    void OnTriggerEnter(Collider other)
    {
        Debug.Log(other.name);
    }

    [ContextMenu("Reset speed")]
    void ResetSpeed()
    {
        speed = 5f;
    }

    void Helper()
    {
    }

    // TYPE:unity-event-function — on the empty line below type `OnColl` (the popup opens; else Ctrl+Space). EXPECT: OnCollisionEnter,
    // OnCollisionStay, OnCollisionExit (and the 2D ones) with "(Collision other)" and "event function"; no Update, no OnTriggerEnter (declared);
    // Enter writes `private void OnCollisionEnter(Collision other)` with an empty body, the caret inside, "Event function" over it.
    // `public OnDis` + Enter writes `public void OnDisable()`. Inside a method body nothing of the kind is offered. Ctrl+Z after each.

#if UNITY_EDITOR
    // UNITY:defines — EXPECT: this branch is live code (UNITY_EDITOR is a symbol of the project), the #else branch below gray
    public static bool InEditor => true;
#else
    public static bool InEditor => false;
#endif
}
