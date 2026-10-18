using NUnit.Framework;
using UnityEngine;

namespace Demo.Tests
{
    // UNITY:tests (0.1.273) — with the editor connected (UNITY:editor-link). EXPECT: ▶ in the gutter of MathTests and of each method;
    // ▶ of the class → "Run 'MathTests'" runs the three tests in Unity, the Unit Tests window shows them green with their durations (the
    // case Clamps(5,0,3) and Clamps(-1,0,3) under their method's name); Unity is not focused and does not need to be. Change 4 to 5 in
    // Max_TakesTheBigger, Ctrl+S, wait for "edit mode" in the status bar, ▶ of the method: one red test, "Expected: 5 But was: 4" and the
    // stack with MathTests.cs. Double click on a test opens it. Stop while running cancels the run in Unity. Undo afterwards.
    public class MathTests
    {
        [Test]
        public void Max_TakesTheBigger()
        {
            Assert.AreEqual(4, Mathf.Max(3, 4));
        }

        [TestCase(5, 0, 3, ExpectedResult = 3)]
        [TestCase(-1, 0, 3, ExpectedResult = 0)]
        public int Clamps(int value, int min, int max) => Mathf.Clamp(value, min, max);
    }
}
