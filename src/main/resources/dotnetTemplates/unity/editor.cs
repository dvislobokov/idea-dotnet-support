using UnityEditor;
using UnityEngine;
---
[CustomEditor(typeof(${BASE}))]
public class ${NAME} : Editor
{
    public override void OnInspectorGUI()
    {
        DrawDefaultInspector();
    }
}
