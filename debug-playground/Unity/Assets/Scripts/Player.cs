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
