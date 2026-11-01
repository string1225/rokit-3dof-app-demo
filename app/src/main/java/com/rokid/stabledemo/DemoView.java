package com.rokid.stabledemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import com.rokid.stabledemo.core.Playback;
import com.rokid.stabledemo.core.PoseState;
import com.rokid.stabledemo.core.Projection;
import com.rokid.stabledemo.core.Quaternion;
import java.util.Locale;

/** Hardware Canvas uses a projective homography for the entire pre-laid-out text plane. */
final class DemoView extends View {
    static final String DEMO_TEXT = "让文字停在眼前的方向。\n轻轻转头，观察画面是否稳定。\n向左、向右，再抬头、低头。\n最后歪一歪头，检查横滚补偿。\n同一段文字会持续循环播放。";
    private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final StaticLayout textLayout;
    private final Playback playback=new Playback(DEMO_TEXT,90,2400);
    private final Projection projection;
    private final Matrix homography=new Matrix();
    private final RectF panel=new RectF(30,94,450,378);
    private final float[] source={30,94,450,94,450,378,30,378};
    private final float[] destination=new float[8];
    private final GestureDetector gestures;
    private final Runnable clickAction;
    private TrackingController tracker;
    private boolean running, stabilized=true, foreground;
    private long startMs=-1, noticeUntilMs;
    private String notice="";
    private Playback.Frame frame=playback.at(0);

    DemoView(Context context, float horizontalFov, Runnable click, Runnable back, Runnable recenter) {
        super(context);
        clickAction=click;
        setClickable(true);
        // Optical see-through requires black pixels even while hardware-key focus is visible.
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("3DoF 打字机演示；单击切换维稳，长按回正，双击返回");
        setFocusable(true);
        line.setColor(Color.GREEN); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(1.5f);
        text.setColor(Color.GREEN); text.setTextSize(23);
        small.setColor(Color.GREEN); small.setTextSize(16);
        textLayout=StaticLayout.Builder.obtain(DEMO_TEXT,0,DEMO_TEXT.length(),text,376)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(12,1).build();
        projection=new Projection(480,480,horizontalFov);
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { performClick(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { back.run(); return true; }
            @Override public void onLongPress(MotionEvent e) { recenter.run(); }
        });
    }

    void begin(TrackingController tracker) {
        this.tracker=tracker; running=true; foreground=true; stabilized=true; startMs=-1;
        frame=playback.at(0); invalidate();
    }
    void end() { running=false; tracker=null; invalidate(); }
    void suspend() { foreground=false; tracker=null; }
    void toggle() { stabilized=!stabilized; invalidate(); }
    void centered() { notice="已回正"; noticeUntilMs=SystemClock.elapsedRealtime()+1200; invalidate(); }
    boolean isRunning() { return running; }
    boolean isStabilized() { return stabilized; }
    String visibleText() { return frame.text; }
    boolean unavailable() {
        return tracker != null && tracker.latest().status.startsWith("IMU 不可用");
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.BLACK);
        if (!running || !foreground || tracker == null) return;
        float scale=Math.min(getWidth()/480f,getHeight()/640f);
        canvas.save();
        canvas.translate((getWidth()-480*scale)/2,(getHeight()-640*scale)/2);
        canvas.scale(scale,scale); canvas.translate(0,80);
        canvas.clipRect(0,0,480,480);
        TrackingController.Snapshot state=tracker.latest();
        long now=SystemClock.elapsedRealtime();
        boolean fresh=state.ready && PoseState.isFresh(state.timestampNs,SystemClock.elapsedRealtimeNanos());
        if (fresh && startMs < 0) startMs=now;
        frame=playback.at(startMs < 0 ? 0 : now-startMs);
        drawStatus(canvas,state,fresh,now);
        if (fresh) {
            Quaternion head=stabilized ? state.relative : Quaternion.IDENTITY;
            boolean visible=true;
            for (int i=0;i<4;i++) {
                double[] projected=projection.point(source[2*i],source[2*i+1],head);
                if (projected == null || !Double.isFinite(projected[0]) || !Double.isFinite(projected[1])) {
                    visible=false; break;
                }
                destination[2*i]=(float)projected[0]; destination[2*i+1]=(float)projected[1];
            }
            if (visible && homography.setPolyToPoly(source,0,destination,0,4)) {
                canvas.save(); canvas.concat(homography); drawTextPanel(canvas,now); canvas.restore();
            }
        } else {
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(22);
            canvas.drawText(state.ready ? "IMU 数据中断" : state.status,240,218,small);
            small.setTextSize(16);
            canvas.drawText(state.ready ? "长按重新连接 · 双击返回" : "保持头部静止片刻",240,256,small);
            small.setTextAlign(Paint.Align.LEFT);
        }
        canvas.drawLine(20,422,460,422,line);
        small.setTextSize(16);
        canvas.drawText("单击：维稳开关   长按：回正",20,447,small);
        canvas.drawText("双击：返回   ·   仅补偿头部旋转",20,473,small);
        canvas.restore();
        postInvalidateOnAnimation();
    }

    private void drawStatus(Canvas canvas,TrackingController.Snapshot state,boolean fresh,long now) {
        small.setTextSize(19);
        canvas.drawText(stabilized ? "3DoF 维稳 · 开" : "3DoF 维稳 · 关",20,25,small);
        small.setTextAlign(Paint.Align.RIGHT); small.setTextSize(15);
        canvas.drawText("循环 "+frame.round,460,25,small);
        small.setTextAlign(Paint.Align.LEFT);
        String status=fresh ? String.format(Locale.ROOT,"%s  %.0f Hz",state.status,state.hz)
                : state.ready ? "数据中断 · 未维稳" : state.status;
        canvas.drawText(now < noticeUntilMs ? notice : status,20,55,small);
    }

    private void drawTextPanel(Canvas canvas,long now) {
        canvas.drawRoundRect(panel,8,8,line);
        canvas.drawLine(46,111,64,111,line);
        canvas.drawLine(46,111,46,129,line);
        canvas.save(); canvas.translate(52,127);
        int visibleEnd=frame.text.length();
        for (int i=0;i<textLayout.getLineCount();i++) {
            int start=textLayout.getLineStart(i), end=Math.min(textLayout.getLineEnd(i),visibleEnd);
            if (end <= start) break;
            if (DEMO_TEXT.charAt(end-1)=='\n') end--;
            if (end > start) canvas.drawText(DEMO_TEXT,start,end,0,textLayout.getLineBaseline(i),text);
        }
        if (now%1000 < 600) {
            int cursorLine=textLayout.getLineForOffset(visibleEnd);
            float x=textLayout.getPrimaryHorizontal(visibleEnd);
            float y=textLayout.getLineBaseline(cursorLine);
            canvas.drawLine(x+3,y-20,x+3,y+3,line);
        }
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) { return gestures.onTouchEvent(event); }
    @Override public boolean performClick() { super.performClick(); clickAction.run(); return true; }
}
