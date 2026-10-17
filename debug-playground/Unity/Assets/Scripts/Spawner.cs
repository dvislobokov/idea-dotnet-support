using UnityEngine;

// UNITY:editor-messages — a ScriptableObject: EXPECT "Unity script" over the class, "Event function" over OnEnable, "Set by Unity" over
// count; `Upd` on the empty line below offers nothing of Update (a ScriptableObject has no Update), `OnVal` offers OnValidate.
[CreateAssetMenu(fileName = "Spawner", menuName = "Scriptable Objects/Spawner")]
public class Spawner : ScriptableObject
{
    public int count = 3;

    void OnEnable()
    {
    }

}
