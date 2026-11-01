package com.rokid.stabledemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import com.rokid.stabledemo.core.Playback;
import com.rokid.stabledemo.power.PowerBenchmark;

/** Identical screen workload across all five phases; no IMU or motion analysis. */
final class PowerTestView extends View {
    private final Paint green=new Paint(Paint.ANTI_ALIAS_FLAG),border=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Playback playback=new Playback(DemoView.DEMO_TEXT,90,2400);
    private final StaticLayout layout;
    private final GestureDetector gestures;
    private PowerBenchmark benchmark;
    private boolean foreground;
    private long startedMs,stageDuration;
    PowerTestView(Context context,Runnable back) {
        super(context); setFocusable(true); setDefaultFocusHighlightEnabled(false);
        green.setColor(Color.GREEN); text.setColor(Color.GREEN); text.setTextSize(18);
        border.setColor(Color.GREEN); border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(1.5f);
        layout=StaticLayout.Builder.obtain(DemoView.DEMO_TEXT,0,DemoView.DEMO_TEXT.length(),text,376)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(7,1).build();
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { back.run(); return true; }
        });
    }
    void attach(PowerBenchmark benchmark,long durationMs) { this.benchmark=benchmark; stageDuration=durationMs; startedMs=SystemClock.elapsedRealtime(); invalidate(); }
    void foreground(boolean value) { foreground=value; if(value) invalidate(); }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); c.drawColor(Color.BLACK); if(!foreground||benchmark==null) return;
        float scale=Math.min(getWidth()/480f,getHeight()/640f); c.save();
        c.translate((getWidth()-480*scale)/2,(getHeight()-640*scale)/2); c.scale(scale,scale); c.translate(0,80); c.clipRect(0,0,480,480);
        green.setTextSize(22); c.drawText("续航测试",20,26,green); green.setTextSize(16);
        String[] lines=benchmark.screenLines();
        int count=Math.min(lines.length-1,benchmark.isFinished()?9:8);
        for(int i=0;i<count;i++) {
            int index=i+1;
            if(benchmark.isFinished() && lines.length>10 && i>=count-2) index=lines.length-(count-i);
            String line=lines[index]; int length=green.breakText(line,true,440,null);
            c.drawText(line.substring(0,length),20,56+i*20,green);
        }
        c.drawRect(30,223,450,399,border);
        int end=playback.at(SystemClock.elapsedRealtime()-startedMs).text.length(); c.save(); c.translate(52,237);
        for(int i=0;i<layout.getLineCount();i++) {
            int begin=layout.getLineStart(i),stop=Math.min(end,layout.getLineEnd(i)); if(stop<=begin) break;
            if(DemoView.DEMO_TEXT.charAt(stop-1)=='\n') stop--;
            if(stop>begin) c.drawText(DemoView.DEMO_TEXT,begin,stop,0,layout.getLineBaseline(i),text);
        }
        c.restore(); green.setTextSize(14);
        c.drawText(stageDuration==300_000?"每项5分钟 · 初始化与保存另计":"缩时验收：每项"+(stageDuration/1000)+"秒，非25分钟",20,421,green);
        c.drawText("固定显示负载 · 未启用IMU · 整机耗电",20,445,green);
        c.drawText("双击/返回：中止并保存",20,473,green);
        c.restore(); if(benchmark.isRunning()) postInvalidateOnAnimation();
    }
    @Override public boolean onTouchEvent(MotionEvent event) { return gestures.onTouchEvent(event); }
}
