using UnityEngine;
using UnityEngine.Events;

// UNITY_PLAN.md, the asset index (0.1.260): Assets/Scenes/Boot.unity holds a Door on the GameObject "Front Door" with openSpeed 3, a
// UnityEvent call of Open and a component whose script is gone; Assets/Animations/DoorOpen.anim calls OnDoorOpened by an animation event.
//
// UNITY:asset-lenses — EXPECT over the declarations below: "Used in Boot.unity" over Door (not "Unity script"), "1 usage, set by
// Unity: 3" over openSpeed, "1 usage" over closeDelay (no scene sets it), "Event handler in Boot.unity" over Open, "Animation event in
// DoorOpen.anim" over OnDoorOpened, "no usages" over Close.
//
// UNITY:asset-usages — Alt+F7 (Find Usages) on Door, Open, openSpeed. EXPECT: a group "Usage in Unity assets" with the line of Boot.unity
// (the GUID of m_Script for Door, `m_MethodName: Open`, `openSpeed: 3`); a click opens the scene at that place.
//
// UNITY:scene-links — open Assets/Scenes/Boot.unity. EXPECT: highlighted as YAML, no red in it; Ctrl+B on the guid of the Door's
// m_Script opens this class, Ctrl+B on `Open` of m_MethodName this method; the m_Script of the GUID 0000…dead is yellow —
// "Missing script: no script of the project has the GUID …".
//
// UNITY:formerly-serialized — Shift+F6 on openSpeed, new name speed, Enter. EXPECT: `[FormerlySerializedAs("openSpeed")]` above the
// field and `using UnityEngine.Serialization;` after the usings (Boot.unity keeps the value under the old name); Shift+F6 on closeDelay
// renames it alone (no scene sets it). Ctrl+Z after each.
public class Door : MonoBehaviour
{
    [SerializeField] private float openSpeed = 1f;
    [SerializeField] private float closeDelay = 2f;
    public UnityEvent opened;

    public void Open()
    {
        opened.Invoke();
    }

    void OnDoorOpened()
    {
        Debug.Log($"{name} opened at {openSpeed}, closing in {closeDelay}");
    }

    void Close()
    {
    }
}
