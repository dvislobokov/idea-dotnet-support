// docs: local-function-errors.md #1; codes: CS8108 CS8112 CS8321 CS8421 CS8422
public class C
{
    private int counter = 1;

    public void IncreaseCounter()
    {
        static void LocalFunc(int addition)
        {
            this.counter += addition;   // CS8422
            base.ToString();            // CS8422

            counter += addition;        // CS8422: implicit this
            ToString();                 // CS8422: implicit base
        }

        LocalFunc(10);
    }
}
