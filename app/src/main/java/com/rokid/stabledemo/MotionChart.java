package com.rokid.stabledemo;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import com.rokid.stabledemo.core.MotionDiagnostics;
import java.util.Locale;

/** Screen-fixed chart. One shared angular scale; dashed line is modeled residual, not optical ground truth. */
final class MotionChart {
    private final Paint input=new Paint(Paint.ANTI_ALIAS_FLAG),residual=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide=new Paint(Paint.ANTI_ALIAS_FLAG),label=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    MotionChart() {
        for(Paint p:new Paint[]{input,residual,guide}) { p.setColor(Color.GREEN); p.setStyle(Paint.Style.STROKE); }
        input.setStrokeWidth(1.5f); residual.setStrokeWidth(2);
        residual.setPathEffect(new DashPathEffect(new float[]{5,3},0)); guide.setAlpha(100);
        label.setColor(Color.GREEN); label.setTextSize(14);
    }
    void draw(Canvas canvas,MotionDiagnostics.Snapshot s) {
        double amplitude=.2;
        for(double v:s.inputCurve) amplitude=Math.max(amplitude,Math.abs(v));
        for(double v:s.residualCurve) amplitude=Math.max(amplitude,Math.abs(v));
        amplitude=Math.ceil(amplitude*10)/10;
        canvas.drawText(String.format(Locale.ROOT,"俯仰°  输入 ━   估计残差 ┄  ±%.1f°",amplitude),20,276,label);
        canvas.drawRect(30,284,450,370,guide); canvas.drawLine(30,327,450,327,guide);
        for(int i=1;i<4;i++) canvas.drawLine(30+i*105,284,30+i*105,370,guide);
        canvas.save(); canvas.clipRect(30,284,450,370);
        trace(canvas,s.seconds,s.inputCurve,amplitude,input);
        trace(canvas,s.seconds,s.residualCurve,amplitude,residual);
        canvas.restore();
        canvas.drawText("最近4秒 · 残差基于预计显示时间，非实测",20,389,label);
    }
    private void trace(Canvas canvas,double[] seconds,double[] values,double amplitude,Paint paint) {
        path.reset();
        for(int i=0;i<values.length;i++) {
            float x=(float)(450+seconds[i]*105),y=(float)(327-values[i]/amplitude*40);
            if(i==0) path.moveTo(x,y); else path.lineTo(x,y);
        }
        canvas.drawPath(path,paint);
    }
}
