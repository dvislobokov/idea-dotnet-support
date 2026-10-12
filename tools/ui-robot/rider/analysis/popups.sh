cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/rider/analysis/rj.sh
F=Console/Editor/RiderAnalysis.cs
pop $F "class Circle" 7 ShowIntentionActions ""
pop $F "string.Format(" 3 ShowIntentionActions 20-alt-enter-string-format.png
pop $F "string title" 9 ShowIntentionActions 22-alt-enter-ctor-parameter.png
pop $F "var unused" 6 ShowIntentionActions ""
pop $F "nullable.Length" 2 ShowIntentionActions ""
pop $F "(string)boxed" 3 ShowIntentionActions ""
pop $F "foreach" 0 ShowIntentionActions ""
pop $F "\$\"Order {" 3 ShowIntentionActions ""
pop $F "if (order.Status ==" 1 ShowIntentionActions 23-alt-enter-if.png
pop $F "from o in" 2 ShowIntentionActions ""
pop $F "public string Customer" 16 ShowIntentionActions ""
pop $F "public void Place" 13 ShowIntentionActions ""
pop $F "public int Id" 2 Generate 24-generate-alt-insert.png
pop $F "public void Place" 13 Refactorings.QuickListPopupAction 25-refactor-this.png
pop $F "Order order)" 2 ReSharperNavigateTo 26-navigate-to.png
pop $F "public abstract class ShapeBase" 25 ReSharperNavigateTo ""
pop $F "public interface IShape" 18 Generate ""
pop $F "public void Place" 13 EditorPopupMenu ""
