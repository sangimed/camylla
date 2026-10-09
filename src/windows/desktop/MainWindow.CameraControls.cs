using Camylla.Receiver;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Automation;

namespace Camylla.Desktop;

public sealed partial class MainWindow
{
    private async void OnPreviewFocus(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs args)
    {
        if (imageSource == null || closing) return;
        var point = args.GetCurrentPoint(PreviewImage).Position;
        var scale = Math.Min(PreviewImage.ActualWidth / imageSource.PixelWidth, PreviewImage.ActualHeight / imageSource.PixelHeight);
        if (scale <= 0) return;
        var width = imageSource.PixelWidth * scale; var height = imageSource.PixelHeight * scale;
        var x = (point.X - (PreviewImage.ActualWidth-width)/2)/width;
        var y = (point.Y - (PreviewImage.ActualHeight-height)/2)/height;
        if (x < 0 || x > 1 || y < 0 || y > 1) return;
        try {
            var result = await receiver.SetCameraFocusAsync(x,y,stop.Token);
            cameraResultText = result.Error ?? "Focus target applied. Waiting for autofocus result.";
        } catch (Exception error) { cameraResultText = error.Message; }
    }
    private sealed class CameraEditor(CameraControlCapability capability, TextBlock label, Slider? slider, ComboBox? choice)
    {
        public CameraControlCapability Capability { get; } = capability;
        public TextBlock Label { get; } = label;
        public Slider? Slider { get; } = slider;
        public ComboBox? Choice { get; } = choice;
        public bool Updating { get; set; }
        public bool Dirty { get; set; }
    }
    private readonly Dictionary<string, CameraEditor> cameraEditors = [];
    private string cameraUiSession = "";
    private int cameraUiGeneration;
    private string cameraResultText = "";
    private void UpdateCameraControls()
    {
        var state = receiver.CameraControls();
        if (state.SessionId != cameraUiSession || state.Generation != cameraUiGeneration ||
            state.Capabilities.Count != cameraEditors.Count || state.Capabilities.Any(pair =>
                !cameraEditors.TryGetValue(pair.Key, out var editor) || editor.Capability != pair.Value))
        {
            cameraUiSession = state.SessionId; cameraUiGeneration = state.Generation;
            cameraEditors.Clear(); CameraControlRows.Children.Clear(); cameraResultText = "";
            foreach (var capability in state.Capabilities.Values)
            {
                var label = new TextBlock { TextWrapping = TextWrapping.Wrap };
                var row = new StackPanel { Spacing = 4 }; row.Children.Add(label);
                Slider? slider = null; ComboBox? choice = null;
                if (capability.UnavailableReason == null)
                {
                    if (capability.Choices.Count > 0)
                    {
                        choice = new ComboBox { HorizontalAlignment = HorizontalAlignment.Stretch };
                        foreach (var option in capability.Choices) choice.Items.Add(new ComboBoxItem { Content = option.Value, Tag = option.Key });
                        AutomationProperties.SetName(choice, capability.Label); row.Children.Add(choice);
                    }
                    else
                    {
                        slider = new Slider { Minimum = capability.Minimum, Maximum = capability.Maximum,
                            StepFrequency = capability.Step, SmallChange = capability.Step, Header = capability.Unit };
                        AutomationProperties.SetName(slider, capability.Label); row.Children.Add(slider);
                    }
                    var apply = new Button { Content = $"Apply {capability.Label}" };
                    var session = state.SessionId;
                    var generation = state.Generation;
                    apply.Click += async (_, _) =>
                    {
                        if (cameraUiSession != session || cameraUiGeneration != generation ||
                            !cameraEditors.TryGetValue(capability.Key, out var editor) || editor.Capability != capability) return;
                        var value = editor.Slider?.Value ?? (editor.Choice?.SelectedItem is ComboBoxItem item ? Convert.ToDouble(item.Tag) : double.NaN);
                        apply.IsEnabled = false; CameraControlStatus.Text = $"Applying {capability.Label}…";
                        try {
                            var result = await receiver.SetCameraControlAsync(capability.Key, value, stop.Token);
                            if (closing) return;
                            if (cameraUiSession != session || cameraUiGeneration != generation ||
                                cameraEditors.GetValueOrDefault(capability.Key) != editor) return;
                            cameraResultText = result.Error ?? $"{capability.Label} applied: {result.Applied:0.###} {capability.Unit}";
                            editor.Dirty = false;
                        }
                        catch (Exception error) {
                            if (cameraUiSession == session && cameraUiGeneration == generation)
                                cameraResultText = error.Message;
                        }
                        finally { apply.IsEnabled = true; }
                    };
                    row.Children.Add(apply);
                }
                var editor = new CameraEditor(capability, label, slider, choice);
                cameraEditors.Add(capability.Key, editor);
                if (slider != null) slider.ValueChanged += (_, _) => { if (!editor.Updating) editor.Dirty = true; };
                if (choice != null) choice.SelectionChanged += (_, _) => { if (!editor.Updating) editor.Dirty = true; };
                CameraControlRows.Children.Add(row);
            }
        }
        CameraControlStatus.Text = state.SessionId.Length == 0 ? "Capture stopped. Start on the phone." :
            $"Focus: {state.Focus} · {state.Message}\n{cameraResultText}";
        foreach (var editor in cameraEditors.Values)
        {
            var c = editor.Capability;
            var value = state.Applied.GetValueOrDefault(c.Key, double.NaN);
            var text = c.UnavailableReason != null ? "Unavailable" : double.IsNaN(value) ? "Awaiting camera" :
                c.Key == "focusMode" && value == 1 ? "Tap autofocus" :
                c.Choices.TryGetValue((int)value, out var name) ? name : $"{value:0.###} {c.Unit}";
            editor.Label.Text = $"{c.Label}: {text}" + (state.Pending.Contains(c.Key) ? " · Applying" : "") +
                (c.UnavailableReason != null ? $"\nUnavailable: {c.UnavailableReason}" : "");
            if (editor.Dirty || !double.IsFinite(value)) continue;
            editor.Updating = true;
            try {
                if (editor.Slider != null) editor.Slider.Value = Math.Clamp(value, c.Minimum, c.Maximum);
                if (editor.Choice != null) editor.Choice.SelectedItem = editor.Choice.Items.OfType<ComboBoxItem>().FirstOrDefault(i => Convert.ToDouble(i.Tag) == value);
            }
            finally { editor.Updating = false; }
        }
    }
}
