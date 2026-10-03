using Microsoft.UI.Xaml;

namespace Camylla.Desktop;

public partial class App : Application
{
    private MainWindow? window;
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        window = new MainWindow();
        window.Activate();
    }
}
