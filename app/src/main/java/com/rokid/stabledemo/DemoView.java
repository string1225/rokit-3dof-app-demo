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
import com.rokid.stabledemo.core.MotionDiagnostics;
import com.rokid.stabledemo.core.LevelFollow;
import java.util.Locale;
import java.util.function.Supplier;

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
    private final RectF panel=new RectF(30,170,450,310);
    private final float[] source={30,170,450,170,450,310,30,310};
    private final float[] destination=new float[8];
    private final GestureDetector gestures;
    private final Runnable clickAction;
    private final MotionChart chart=new MotionChart();
    private final LevelFollow follower;
    private TrackingController tracker;
    private MotionDiagnostics diagnostics;
    private Supplier<String> powerStatus=() -> "";
    private double appFrameMs;
    private boolean running, stabilized=true, foreground;
    private long startMs=-1, noticeUntilMs;
    private long referenceEpoch=Long.MIN_VALUE;
    private String notice="";
    private Playback.Frame frame=playback.at(0);

    DemoView(Context context,float horizontalFov,double followTau,double followSpeed,Runnable click,Runnable back,Runnable recenter) {
        super(context);
        follower=new LevelFollow(followTau,followSpeed);
        clickAction=click;
        setClickable(true);
        // Optical see-through requires black pixels even while hardware-key focus is visible.
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("3DoF 打字机演示；单击切换维稳，长按回正，双击返回");
        setFocusable(true);
        line.setColor(Color.GREEN); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(1.5f);
        text.setColor(Color.GREEN); text.setTextSize(17);
        small.setColor(Color.GREEN); small.setTextSize(16);
        textLayout=StaticLayout.Builder.obtain(DEMO_TEXT,0,DEMO_TEXT.length(),text,376)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(5,1).build();
        projection=new Projection(480,480,horizontalFov);
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { performClick(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { back.run(); return true; }
            @Override public void onLongPress(MotionEvent e) { recenter.run(); }
        });
    }

    void begin(TrackingController tracker,MotionDiagnostics diagnostics) {
        this.tracker=tracker; running=true; foreground=true; stabilized=true; startMs=-1;
        this.diagnostics=diagnostics; appFrameMs=0;
        follower.reset(); referenceEpoch=Long.MIN_VALUE;
        frame=playback.at(0); invalidate();
    }
    void setPowerStatus(Supplier<String> status) { powerStatus=status; }
    void setFrameDurationNs(long duration) {
        if(duration>0 && duration<500_000_000L) appFrameMs=appFrameMs==0?duration*1e-6:appFrameMs*.9+duration*1e-7;
    }
    void end() { running=false; tracker=null; invalidate(); }
    void suspend() { foreground=false; tracker=null; }
    void toggle() {
        stabilized=!stabilized;
        TrackingController.Snapshot state=tracker==null?null:tracker.latest();
        if(stabilized && state!=null && state.ready) {
            follower.recenter(state.relative,SystemClock.elapsedRealtimeNanos()); referenceEpoch=state.referenceEpoch;
        }
        invalidate();
    }
    void centered() {
        TrackingController.Snapshot state=tracker==null?null:tracker.latest();
        if(state!=null && state.ready) {
            follower.recenter(state.relative,SystemClock.elapsedRealtimeNanos()); referenceEpoch=state.referenceEpoch;
        }
        notice="已水平回中"; noticeUntilMs=SystemClock.elapsedRealtime()+1200; invalidate();
    }
    boolean isRunning() { return running; }
    boolean isStabilized() { return stabilized; }
    String visibleText() { return frame.text; }
    boolean unavailable() {
        return tracker != null && tracker.latest().status.startsWith("IMU 不可用");
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.BLACK);
        if (!running || !foreground || tracker == null) return;
        long drawStartNs=SystemClock.elapsedRealtimeNanos();
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
        MotionDiagnostics.Snapshot analysis=diagnostics.snapshot(drawStartNs);
        drawStatus(canvas,state,fresh,now,analysis);
        Quaternion applied=Quaternion.IDENTITY,anchor=Quaternion.IDENTITY;
        if (fresh) {
            if(state.referenceEpoch!=referenceEpoch) {
                follower.recenter(state.relative,drawStartNs); referenceEpoch=state.referenceEpoch;
            }
            if(stabilized) {
                LevelFollow.Result result=follower.update(state.relative,drawStartNs,projection);
                applied=result.compensatedHead; anchor=result.anchor;
                if(result.snapped) { notice="超界 · 已水平回中"; noticeUntilMs=now+1200; }
            } else follower.recenter(state.relative,drawStartNs);
            Quaternion head=applied;
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
            canvas.drawText(state.ready ? "IMU 数据中断" : state.status,240,222,small);
            small.setTextSize(14);
            canvas.drawText(state.ready ? "长按重新连接 · 双击返回" : "保持头部静止片刻",240,253,small);
            small.setTextAlign(Paint.Align.LEFT);
        }
        chart.draw(canvas,analysis);
        small.setTextSize(13);
        canvas.drawText(powerStatus.get(),20,428,small);
        canvas.drawText(String.format(Locale.ROOT,"RMS 输入%s° / 残差%s°  绘制%sHz",
                number(analysis.inputRmsDeg),number(analysis.residualRmsDeg),number(analysis.renderHz)),20,447,small);
        canvas.drawLine(20,456,460,456,line);
        canvas.drawText("单击开关 · 长按水平回中 · 双击返回保存",20,477,small);
        canvas.restore();
        long endNs=SystemClock.elapsedRealtimeNanos();
        if(fresh) {
            double refreshMs=1000.0/(getDisplay()!=null?getDisplay().getRefreshRate():60.0);
            double cpu=(endNs-drawStartNs)*1e-6;
            long estimatedPresentation=drawStartNs+(long)((Math.max(cpu,appFrameMs)+refreshMs)*1e6);
            diagnostics.frameSubmitted(applied,anchor,endNs,Math.max(endNs,estimatedPresentation),state.timestampNs,cpu,appFrameMs,refreshMs);
        }
        postInvalidateOnAnimation();
    }

    private void drawStatus(Canvas canvas,TrackingController.Snapshot state,boolean fresh,long now,MotionDiagnostics.Snapshot s) {
        small.setTextSize(19);
        canvas.drawText(stabilized ? "3DoF · 水平自动回中" : "3DoF 维稳 · 关",20,25,small);
        small.setTextAlign(Paint.Align.RIGHT); small.setTextSize(15);
        canvas.drawText("循环 "+frame.round,460,25,small);
        small.setTextAlign(Paint.Align.LEFT);
        String status=fresh ? String.format(Locale.ROOT,"%s %.0fHz  抖动主频%s / 均频%sHz",state.status,
                Double.isFinite(s.sampleHz)?s.sampleHz:state.hz,number(s.dominantHz),number(s.meanHz))
                : state.ready ? "数据中断 · 未维稳" : state.status;
        small.setTextSize(13);
        canvas.drawText(now < noticeUntilMs ? notice : status,20,46,small);
        canvas.drawText(String.format(Locale.ROOT,"样本%sms  绘制%sms  显示≈%s±%sms",number(s.sampleAgeMs),
                number(s.cpuDrawMs),number(s.estimatedDisplayMs),number(s.uncertaintyMs)),20,65,small);
    }

    private void drawTextPanel(Canvas canvas,long now) {
        canvas.drawRoundRect(panel,8,8,line);
        canvas.save(); canvas.translate(52,181);
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
            canvas.drawLine(x+3,y-16,x+3,y+3,line);
        }
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) { return gestures.onTouchEvent(event); }
    @Override public boolean performClick() { super.performClick(); clickAction.run(); return true; }
    private static String number(double value) { return Double.isFinite(value)?String.format(Locale.ROOT,"%.1f",value):"--"; }
}
