using Microsoft.Windows.AppLifecycle;
using Microsoft.UI.Xaml;

namespace Camylla.Desktop;

public partial class App : Application
{
    private readonly object activationGate = new();
    private MainWindow? window;
    private bool activationPending;
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
    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        var instance = AppInstance.FindOrRegisterForKey("Camylla.Desktop");
        if (!instance.IsCurrent)
        {
            await instance.RedirectActivationToAsync(AppInstance.GetCurrent().GetActivatedEventArgs());
            Exit();
            return;
        }

        instance.Activated += OnInstanceActivated;
        var created = new MainWindow();
        bool activatePending;
        lock (activationGate)
        {
            window = created;
            activatePending = activationPending;
            activationPending = false;
        }
        created.Activate();
        if (activatePending) created.ActivateFromLaunch();
    }

    private void OnInstanceActivated(object? sender, AppActivationArguments args)
    {
        MainWindow? current;
        lock (activationGate)
        {
            current = window;
            if (current == null)
            {
                activationPending = true;
                return;
            }
        }
        current.DispatcherQueue.TryEnqueue(current.ActivateFromLaunch);
    }
}
