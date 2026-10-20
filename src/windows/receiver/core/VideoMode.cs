namespace Camylla.Receiver;

public readonly record struct VideoMode(int Width, int Height, int FramesPerSecond)
{
    public static readonly VideoMode P72030 = new(1280, 720, 30);
    public static readonly VideoMode P1080_30 = new(1920, 1080, 30);
    public static readonly VideoMode P1080_60 = new(1920, 1080, 60);
    public static IReadOnlyList<string> Supported { get; } =
        ["1280x720@30", "1920x1080@30", "1920x1080@60"];

    public static bool TryParse(string? value, out VideoMode mode)
    {
        mode = value switch
        {
            "1280x720@30" => P72030,
            "1920x1080@30" => P1080_30,
            "1920x1080@60" => P1080_60,
            _ => default
        };
        return mode.FramesPerSecond != 0;
    }

    public override string ToString() => $"{Width}x{Height}@{FramesPerSecond}";
}
