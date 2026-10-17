using UnityEditor;
using UnityEngine;
---
public class ${NAME} : EditorWindow
{
    [MenuItem("Window/${BASE}")]
    public static void ShowWindow()
    {
        GetWindow<${NAME}>("${BASE}");
    }

    void OnGUI()
    {
    }
}
