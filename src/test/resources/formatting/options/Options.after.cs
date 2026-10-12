namespace Demo.Formatting {
  public class Options {
    public int Sum(int a, int b) {
      if(a > b) {
        return a - b;
      } else {
        switch(a) {
        case 1:
          return 1;
        }
        return Call (a, b);
      }
    }
    private int Call(int a, int b) => ( a + b );
  }
}
