using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace TiviWidget;

/// <summary>
/// Khung vát hai góc (trên-trái và dưới-phải) viền neon, dùng làm khuôn cho nút kiểu Gaming.
/// Glow = true: vẽ thêm quầng sáng quanh viền.
/// </summary>
public sealed class NeonBorder : Decorator
{
    public static readonly DependencyProperty FillProperty = DependencyProperty.Register(
        nameof(Fill), typeof(Brush), typeof(NeonBorder),
        new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty StrokeProperty = DependencyProperty.Register(
        nameof(Stroke), typeof(Brush), typeof(NeonBorder),
        new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty StrokeThicknessProperty = DependencyProperty.Register(
        nameof(StrokeThickness), typeof(double), typeof(NeonBorder),
        new FrameworkPropertyMetadata(1.5, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty CutProperty = DependencyProperty.Register(
        nameof(Cut), typeof(double), typeof(NeonBorder),
        new FrameworkPropertyMetadata(10.0, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty GlowProperty = DependencyProperty.Register(
        nameof(Glow), typeof(bool), typeof(NeonBorder),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

    public Brush Fill { get => (Brush)GetValue(FillProperty); set => SetValue(FillProperty, value); }
    public Brush Stroke { get => (Brush)GetValue(StrokeProperty); set => SetValue(StrokeProperty, value); }
    public double StrokeThickness { get => (double)GetValue(StrokeThicknessProperty); set => SetValue(StrokeThicknessProperty, value); }
    public double Cut { get => (double)GetValue(CutProperty); set => SetValue(CutProperty, value); }
    public bool Glow { get => (bool)GetValue(GlowProperty); set => SetValue(GlowProperty, value); }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        if (w < 4 || h < 4) return;

        double th = Math.Max(0.5, StrokeThickness);
        double half = th / 2 + (Glow ? 2 : 0);          // chừa chỗ cho quầng sáng
        double c = Math.Min(Cut, Math.Min(w, h) / 2.5);

        var g = new StreamGeometry();
        using (var ctx = g.Open())
        {
            ctx.BeginFigure(new Point(half + c, half), true, true);
            ctx.LineTo(new Point(w - half, half), true, false);
            ctx.LineTo(new Point(w - half, h - half - c), true, false);
            ctx.LineTo(new Point(w - half - c, h - half), true, false);
            ctx.LineTo(new Point(half, h - half), true, false);
            ctx.LineTo(new Point(half, half + c), true, false);
        }
        g.Freeze();

        var stroke = Stroke;
        if (Glow && stroke != null)
        {
            var gb = stroke.CloneCurrentValue();
            gb.Opacity = 0.28;
            dc.DrawGeometry(null, new Pen(gb, th + 4) { LineJoin = PenLineJoin.Round }, g);
        }
        dc.DrawGeometry(Fill, stroke != null ? new Pen(stroke, th) { LineJoin = PenLineJoin.Round } : null, g);
    }
}
