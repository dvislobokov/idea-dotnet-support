using System.Collections;
using UnityEngine;

// UNITY_PLAN.md, the extras of Unity (0.1.258). Open debug-playground/Unity on its own; each marker says what to look at.
public class Checks : MonoBehaviour
{
    // UNITY:inspections — EXPECT: [SerializeField] on the static field gray ("Redundant 'SerializeField'"), Alt+Enter → Remove the attribute
    [SerializeField] private static int total;
    public Rigidbody target;

    // UNITY:signature — EXPECT: `update` yellow "'update' is not an event function: Unity calls 'Update'" (Alt+Enter → Rename to 'Update');
    // `(Collision other)` of OnTriggerEnter yellow, Alt+Enter → Change the signature to (Collider other)
    void update() { }

    void OnTriggerEnter(Collision other)
    {
        // UNITY:compare-tag — EXPECT: the comparison yellow, Alt+Enter → Use CompareTag → `!gameObject.CompareTag("Player")`
        if (gameObject.tag != "Player") { }
    }

    // UNITY:gutter — EXPECT: the Unity icon left of Update ("called every frame (performance-critical)") and of OnTriggerEnter above
    void Update()
    {
        // UNITY:frame — EXPECT: GetComponent weak-yellow "Expensive 'GetComponent' in 'Update', called every frame"; Alt+Enter →
        // Introduce a field and initialize it in 'Awake': `private Rigidbody _rigidbody;`, Awake with the assignment, `_rigidbody.AddForce`
        GetComponent<Rigidbody>().AddForce(Vector3.up);
        // UNITY:null-check — EXPECT: `?.` and `??` yellow: a destroyed object is not null to them
        target?.AddForce(Vector3.up);
        var body = target ?? GetComponent<Rigidbody>();
        // UNITY:get-component-type — EXPECT: `string` red "'string' is not a 'Component' or an interface"
        var wrong = GetComponent<string>();
    }

    void Start()
    {
        // TYPE:unity-strings — inside the quotes press Ctrl+Space. EXPECT: CompareTag(" → Untagged, Player, MainCamera..., Enemy, Pickup;
        // NameToLayer(" → Default, Water, UI...; LoadScene(" → the scenes of the build (none in this playground: EditorBuildSettings has none);
        // Input.GetAxis(" → Horizontal, Vertical, Jump. UNITY:unknown-names — EXPECT: "Enmy" yellow "The tag 'Enmy' is not defined in Tags and Layers"
        if (CompareTag("Enemy") || CompareTag("Enmy")) { }
        var layer = LayerMask.NameToLayer("Water");
        var axis = Input.GetAxis("Horizontal");

        // TYPE:unity-get-component — between the angle brackets press Ctrl+Space. EXPECT: Player, Checks (the components of the project), then
        // Rigidbody, Collider, Animator... above the other types
        var player = GetComponent<Player>();
    }

    // TYPE:unity-templates — on the empty line below type `sfield` and Tab. EXPECT: `[SerializeField] private float speed;` with float and
    // speed to edit; `coroutine` + Tab → a coroutine with `yield return null;`. Not offered in a plain .NET project. Ctrl+Z after each.

    // UNITY:meta — in the Solution view rename Checks.cs (Shift+F6) to Checks2.cs. EXPECT: Checks.cs.meta becomes Checks2.cs.meta (Show .meta
    // Files to see it). Rename it back.
    IEnumerator Blink()
    {
        yield return null;
    }
}
