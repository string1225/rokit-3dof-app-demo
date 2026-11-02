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
    private final Paint baseline=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide=new Paint(Paint.ANTI_ALIAS_FLAG),label=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    MotionChart() {
        for(Paint p:new Paint[]{input,residual,baseline,guide}) { p.setColor(Color.GREEN); p.setStyle(Paint.Style.STROKE); }
        input.setStrokeWidth(1.5f); residual.setStrokeWidth(2);
        residual.setPathEffect(new DashPathEffect(new float[]{5,3},0)); guide.setAlpha(100);
        baseline.setStrokeWidth(1.5f); baseline.setPathEffect(new DashPathEffect(new float[]{1,3},0)); baseline.setAlpha(180);
        label.setColor(Color.GREEN); label.setTextSize(12);
    }
    void draw(Canvas canvas,MotionDiagnostics.Snapshot s) {
        double amplitude=.2;
        for(double v:s.inputCurve) amplitude=Math.max(amplitude,Math.abs(v));
        for(double v:s.residualCurve) amplitude=Math.max(amplitude,Math.abs(v));
        for(double v:s.baselineResidualCurve) amplitude=Math.max(amplitude,Math.abs(v));
        amplitude=Math.ceil(amplitude*10)/10;
        canvas.drawText(String.format(Locale.ROOT,"俯仰° 输入━ 普通残差·· 当前残差┄ ±%.1f°",amplitude),20,329,label);
        canvas.drawRect(30,338,450,394,guide); canvas.drawLine(30,366,450,366,guide);
        for(int i=1;i<4;i++) canvas.drawLine(30+i*105,338,30+i*105,394,guide);
        canvas.save(); canvas.clipRect(30,338,450,394);
        trace(canvas,s.seconds,s.inputCurve,amplitude,input);
        trace(canvas,s.seconds,s.baselineResidualCurve,amplitude,baseline);
        trace(canvas,s.seconds,s.residualCurve,amplitude,residual);
        canvas.restore();
        canvas.drawText("最近4秒 · 已排除主动回中 · 残差非实测",20,409,label);
    }
    private void trace(Canvas canvas,double[] seconds,double[] values,double amplitude,Paint paint) {
        path.reset();
        for(int i=0;i<values.length;i++) {
            float x=(float)(450+seconds[i]*105),y=(float)(366-values[i]/amplitude*26);
            if(i==0) path.moveTo(x,y); else path.lineTo(x,y);
        }
        canvas.drawPath(path,paint);
    }
}
