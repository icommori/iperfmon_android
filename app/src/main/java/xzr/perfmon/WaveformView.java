package xzr.perfmon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayDeque;

public class WaveformView extends View {

    static class Sample {
        int cpu;
        int gpu;
        float temp;
    }

    private int colorCpu;
    private int colorGpu;
    private int colorTemp;
    private int colorTempHot;
    private int colorGrid;
    private int colorText;

    private static final int MAX_POINTS = 60;
    public static final int MAX_TEMP = 65;

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    private final Paint paintCpu = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGpu = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTemp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private float yMax = 100f;

    public WaveformView(Context ctx) {
        super(ctx);
        initPaints();
    }

    private void initPaints() {
        colorCpu = getContext().getColor(R.color.cpu_main);
        colorGpu = getContext().getColor(R.color.gpu_main);
        colorTemp = getContext().getColor(R.color.temp_main);
        colorTempHot = getContext().getColor(R.color.temp_hot);
        colorGrid = getContext().getColor(R.color.waveform_grid);
        colorText = getContext().getColor(R.color.waveform_text);

        paintCpu.setStyle(Paint.Style.STROKE);
        paintCpu.setStrokeWidth(4f);
        paintCpu.setColor(colorCpu);

        paintGpu.setStyle(Paint.Style.STROKE);
        paintGpu.setStrokeWidth(4f);
        paintGpu.setColor(colorGpu);

        paintTemp.setStyle(Paint.Style.STROKE);
        paintTemp.setStrokeWidth(4f);

        paintGrid.setColor(colorGrid);
        paintGrid.setStrokeWidth(1f);

        paintText.setColor(colorText);
        paintText.setTextSize(24f);
    }

    public void addSample(int cpu, int gpu, float temp) {
        if (samples.size() >= MAX_POINTS) {
            samples.pollFirst();
        }

        Sample s = new Sample();
        s.cpu = cpu;
        s.gpu = gpu;
        s.temp = temp;
        samples.addLast(s);

        computeYMax();
        postInvalidate();
    }

    private void computeYMax() {
        float max = 50f;
        for (Sample s : samples) {
            if (s.cpu > max) max = s.cpu;
            if (s.gpu > max) max = s.gpu;
            if (s.temp > max) max = s.temp;
        }
        if (max <= 100) yMax = 100;
        else yMax = ((int) (max / 20) + 1) * 20;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float left = 80f, top = 20f, bottom = h - 40f;
        float gh = bottom - top;
        float stepX = (w - left) / (MAX_POINTS - 1);

        LinearGradient bgGradient = new LinearGradient(0, 0, 0, h, Color.BLACK, Color.DKGRAY, Shader.TileMode.CLAMP);
        paintBg.setShader(bgGradient);
        c.drawRect(0, 0, w, h, paintBg);
        paintBg.setShader(null);

        for (int i = 0; i <= 5; i++) {
            float y = top + gh * i / 5f;
            c.drawLine(left, y, w, y, paintGrid);
            int labelValue = (int) (yMax * (1f - i / 5f));
            c.drawText(String.valueOf(labelValue), 10f, y + 8f, paintText);
        }

        if (samples.size() < 2) return;

        // 繪製波形
        drawSmoothPath(c, left, top, gh, stepX, paintCpu, s -> s.cpu);
        drawSmoothPath(c, left, top, gh, stepX, paintGpu, s -> s.gpu);
        drawTemperaturePath(c, left, top, gh, stepX);
    }

    private interface Getter {
        float get(Sample s);
    }

    private void drawSmoothPath(Canvas c, float left, float top, float gh, float stepX, Paint paint, Getter g) {
        mPath.reset();
        int i = 0;
        Sample prev = null;
        for (Sample s : samples) {
            float x = left + i * stepX;
            float y = top + (1f - g.get(s) / yMax) * gh;
            if (i == 0) {
                mPath.moveTo(x, y);
            } else {
                float prevX = left + (i - 1) * stepX;
                float prevY = top + (1f - g.get(prev) / yMax) * gh;
                mPath.quadTo(prevX, prevY, (prevX + x) / 2, (prevY + y) / 2);
            }
            prev = s;
            i++;
        }
        c.drawPath(mPath, paint);
    }

    private void drawTemperaturePath(Canvas c, float left, float top, float gh, float stepX) {
        mPath.reset();
        int i = 0;
        Sample prev = null;
        int currentColor = (samples.peekFirst().temp > MAX_TEMP) ? colorTempHot : colorTemp;

        for (Sample s : samples) {
            float x = left + i * stepX;
            float y = top + (1f - s.temp / yMax) * gh;
            int pointColor = (s.temp > MAX_TEMP) ? colorTempHot : colorTemp;

            if (i == 0) {
                mPath.moveTo(x, y);
            } else {
                if (pointColor != currentColor) {
                    paintTemp.setColor(currentColor);
                    c.drawPath(mPath, paintTemp);
                    mPath.reset();
                    mPath.moveTo(left + (i - 1) * stepX, top + (1f - prev.temp / yMax) * gh);
                    currentColor = pointColor;
                }
                float prevX = left + (i - 1) * stepX;
                float prevY = top + (1f - prev.temp / yMax) * gh;
                mPath.quadTo(prevX, prevY, (prevX + x) / 2, (prevY + y) / 2);
            }
            prev = s;
            i++;
        }
        paintTemp.setColor(currentColor);
        c.drawPath(mPath, paintTemp);
    }
}