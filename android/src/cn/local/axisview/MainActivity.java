package cn.local.axisview;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

public class MainActivity extends Activity implements SensorEventListener {
    private SensorManager sensors;
    private Sensor rotationSensor;
    private AxisView content;
    private int selected = -1;
    private boolean registered = false;
    private boolean received = false;
    private long lastTimestamp = 0;
    private final float[] matrix = new float[9];
    private final double[] angles = {Double.NaN, Double.NaN, Double.NaN};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!registered) return;
            if (SystemClock.elapsedRealtime() - lastTimestamp > 5000) {
                content.status = received ? "等待更新" : "暂无数据 · 请转动手机";
                content.invalidate();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        // Fused gyro + gravity: relative yaw avoids compass interference.
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (saved != null) selected = saved.getInt("selected", -1);
        content = new AxisView(this);
        setContentView(content);
        updateOrientation();
        hideSystemBars();
    }

    @Override protected void onResume() {
        super.onResume();
        received = false;
        for (int i = 0; i < 3; i++) angles[i] = Double.NaN;
        if (rotationSensor == null) {
            content.status = "未检测到姿态传感器";
        } else {
            registered = sensors.registerListener(this, rotationSensor, 20000); // 50 Hz.
            content.status = registered ? "等待传感器" : "传感器连接失败";
            lastTimestamp = SystemClock.elapsedRealtime();
            if (registered) handler.postDelayed(watchdog, 1000);
        }
        content.invalidate();
        hideSystemBars();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(watchdog);
        sensors.unregisterListener(this);
        registered = false;
        super.onPause();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!registered) return;
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        AngleMath.fromMatrix(matrix, angles);
        received = true;
        lastTimestamp = SystemClock.elapsedRealtime();
        content.status = "实时读取";
        content.postInvalidateOnAnimation();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void updateOrientation() {
        setRequestedOrientation(selected < 0
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        content.setContentDescription(selected < 0 ? "三轴角度。轻触一个轴进入横屏。" : "单轴横屏。轻触屏幕返回三轴。");
        content.invalidate();
    }

    private void showAxis(int axis) { selected = axis; updateOrientation(); hideSystemBars(); }
    private void showOverview() { selected = -1; updateOrientation(); hideSystemBars(); }

    @Override public void onBackPressed() {
        if (selected >= 0) showOverview(); else super.onBackPressed();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        hideSystemBars();
        content.requestApplyInsets();
        content.invalidate();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected", selected);
        super.onSaveInstanceState(state);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private final class AxisView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface digits = Typeface.create("sans-serif-light", Typeface.NORMAL);
        private final float density = getResources().getDisplayMetrics().density;
        private final String[] names = {"X 轴", "Y 轴", "Z 轴"};
        private final String[] descriptions = {"前后倾斜", "左右倾斜", "平面转向"};
        private String status = "等待传感器";
        private int insetLeft, insetTop, insetRight, insetBottom;
        private float downX, downY;
        private long downAt;

        AxisView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            setClickable(true);
            setFocusable(true);
            setOnApplyWindowInsetsListener((v, insets) -> {
                if (Build.VERSION.SDK_INT >= 28) {
                    DisplayCutout cutout = insets.getDisplayCutout();
                    insetLeft = cutout == null ? 0 : cutout.getSafeInsetLeft();
                    insetTop = cutout == null ? 0 : cutout.getSafeInsetTop();
                    insetRight = cutout == null ? 0 : cutout.getSafeInsetRight();
                    insetBottom = cutout == null ? 0 : cutout.getSafeInsetBottom();
                }
                invalidate();
                return insets;
            });
        }

        private float dp(float value) { return value * density; }
        private void text(Canvas c, String value, float x, float baseline, float size, int color, Paint.Align align, Typeface typeface) {
            paint.setTypeface(typeface);
            paint.setTextSize(size);
            paint.setColor(color);
            paint.setTextAlign(align);
            c.drawText(value, x, baseline, paint);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawColor(Color.BLACK);
            float left = insetLeft, top = insetTop;
            float width = getWidth() - insetLeft - insetRight;
            float height = getHeight() - insetTop - insetBottom;
            float pad = dp(24);
            if (selected < 0) {
                text(c, "三轴角度", left + pad, top + dp(31), dp(14), Color.WHITE, Paint.Align.LEFT, regular);
                text(c, status, left + width - pad, top + dp(31), dp(11), 0xff999999, Paint.Align.RIGHT, regular);
                float gridTop = top + dp(52);
                float gridHeight = Math.max(dp(60), height - dp(78));
                for (int axis = 0; axis < 3; axis++) {
                    float panelTop = gridTop + axis * gridHeight / 3;
                    float panelHeight = gridHeight / 3;
                    drawPanel(c, axis, left, panelTop, width, panelHeight, false);
                    if (axis < 2) {
                        paint.setColor(0xff242424);
                        paint.setStrokeWidth(dp(1));
                        c.drawLine(left + pad, panelTop + panelHeight, left + width - pad, panelTop + panelHeight, paint);
                    }
                }
                text(c, "轻触任意轴 · 横屏放大", left + width / 2, top + height - dp(9), dp(10), 0xff777777, Paint.Align.CENTER, regular);
            } else {
                text(c, "‹  返回三轴", left + pad, top + dp(33), dp(13), 0xffcccccc, Paint.Align.LEFT, regular);
                text(c, status, left + width - pad, top + dp(33), dp(11), 0xff999999, Paint.Align.RIGHT, regular);
                drawPanel(c, selected, left, top + dp(50), width, height - dp(58), true);
            }
        }

        private void drawPanel(Canvas c, int axis, float left, float top, float width, float height, boolean full) {
            float pad = dp(24);
            text(c, names[axis], left + pad, top + dp(24), dp(full ? 17 : 14), 0xffcccccc, Paint.Align.LEFT, regular);
            text(c, descriptions[axis], left + width - pad, top + dp(24), dp(11), 0xff888888, Paint.Align.RIGHT, regular);
            String value = AngleMath.format(angles[axis], axis) + "°";
            paint.setTypeface(digits);
            float size = Math.min(height * (full ? .64f : .49f), width * (full ? .29f : .24f));
            paint.setTextSize(size);
            float textWidth = paint.measureText(value);
            if (textWidth > width - 2 * pad) size *= (width - 2 * pad) / textWidth;
            paint.setTextSize(size);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float centerY = top + dp(25) + (height - dp(25)) / 2;
            float baseline = centerY - (metrics.ascent + metrics.descent) / 2;
            text(c, value, left + width / 2, baseline, size, Color.WHITE, Paint.Align.CENTER, digits);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX(); downY = event.getY(); downAt = event.getEventTime();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > slop || event.getEventTime() - downAt > 700) return true;
                if (selected >= 0) { performClick(); showOverview(); return true; }
                float gridTop = insetTop + dp(52);
                float gridHeight = getHeight() - insetTop - insetBottom - dp(78);
                if (gridHeight > 0 && event.getY() >= gridTop && event.getY() < gridTop + gridHeight) {
                    int axis = Math.min(2, (int) ((event.getY() - gridTop) / (gridHeight / 3)));
                    performClick(); showAxis(axis);
                }
                return true;
            }
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}
