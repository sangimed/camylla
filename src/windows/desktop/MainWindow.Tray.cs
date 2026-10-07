using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using System.Drawing;
using System.Windows.Forms;

namespace Camylla.Desktop;

public sealed partial class MainWindow
{
    private NotifyIcon? trayIcon;
    private ContextMenuStrip? trayMenu;
    private bool exitRequested;

    private void InitializeTray()
    {
        trayMenu = new ContextMenuStrip();
        trayMenu.Items.Add("Open Camylla", null, (_, _) => DispatcherQueue.TryEnqueue(ShowFromTray));
        trayMenu.Items.Add("Exit Camylla", null, (_, _) => DispatcherQueue.TryEnqueue(() => _ = ExitAsync()));
        trayIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "Camylla",
            ContextMenuStrip = trayMenu,
            Visible = true,
        };
        trayIcon.DoubleClick += (_, _) => DispatcherQueue.TryEnqueue(ShowFromTray);
        AppWindow.Closing += OnAppWindowClosing;
    }

    private void OnAppWindowClosing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (exitRequested || closing) return;
        if (!receiver.Snapshot().CaptureMayBeActive) return;
        args.Cancel = true;
        AppWindow.Hide();
    }

    private void ShowFromTray()
    {
        if (closing) return;
        AppWindow.Show();
        Activate();
    }

    private void OnExit(object sender, RoutedEventArgs args) => _ = ExitAsync();

    private async Task ExitAsync()
    {
        if (exitRequested || closing) return;
        exitRequested = true;
        try
        {
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            await receiver.RequestActivePhoneStopAsync(deadline.Token);
        }
        catch (Exception error)
        {
            System.Diagnostics.Debug.WriteLine($"Desktop Exit stop request: {error}");
        }
        Close();
    }

    private void DisposeTray()
    {
        AppWindow.Closing -= OnAppWindowClosing;
        if (trayIcon != null) { trayIcon.Visible = false; trayIcon.Dispose(); trayIcon = null; }
        trayMenu?.Dispose();
        trayMenu = null;
    }
}
