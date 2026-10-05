using System.Windows;

namespace LegacyWpf
{
    public partial class MainWindow : Window
    {
        private int clicks;

        public MainWindow()
        {
            InitializeComponent();
            // Greeting is a field the XAML compiler generates: with the .NET SDK there is none and the build fails
            Greeting.Text = "Built by MSBuild of Visual Studio";
        }

        private void OnClick(object sender, RoutedEventArgs e)
        {
            clicks++;
            Greeting.Text = "Clicks: " + clicks; // BP:legacy-wpf-click — Debug (or Attach to Process), click the button: stops here; EXPECT: clicks is the number of clicks
        }
    }
}
