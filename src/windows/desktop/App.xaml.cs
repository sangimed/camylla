using Microsoft.UI.Xaml;

namespace Camylla.Desktop;

public partial class App : Application
{
    private MainWindow? window;
    public App()
    {
        UnhandledException += (_, args) => RecordStartupFailure(args.Exception);
        InitializeComponent();
    }

    private static void RecordStartupFailure(System.Exception exception)
    {
        try
        {
            var directory = System.IO.Path.Combine(System.Environment.GetFolderPath(System.Environment.SpecialFolder.LocalApplicationData), "Camylla");
            System.IO.Directory.CreateDirectory(directory);
            System.IO.File.WriteAllText(System.IO.Path.Combine(directory, $"desktop-failure-{System.DateTime.UtcNow:yyyyMMddTHHmmssfff}-{System.Guid.NewGuid():N}.txt"), exception.ToString());
        }
        catch { /* Keep the original exception and normal crash behavior. */ }
    }
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        window = new MainWindow();
        window.Activate();
    }
}
