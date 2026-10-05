using Camylla.Receiver;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Camylla.Desktop;

public sealed partial class MainWindow
{
    private async void OnRemoveLegacyCredentials(object sender, RoutedEventArgs args)
    {
        if (pairDialogShowing || rotationDialogShowing || replacementDialogShowing) return;
        var dialog = new ContentDialog
        {
            XamlRoot = ((FrameworkElement)Content).XamlRoot,
            Title = "Remove old connection credentials?",
            Content = "Remove the old development password and certificate/private-key file. Your QR pairing and saved phones are unaffected. Old diagnostic receiver mode will require new credentials.",
            PrimaryButtonText = "Remove old credentials", CloseButtonText = "Cancel"
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        try
        {
            LegacyMigration.RemoveCredentials();
            LegacyCleanupButton.Visibility = Visibility.Collapsed;
            ConnectionHelp.Text = "Old credentials removed. Saved QR pairing is ready to use.";
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        { ConnectionHelp.Text = "Some old credentials could not be removed. QR pairing remains available; retry cleanup."; }
    }
}
