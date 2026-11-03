package com.rokid.stabledemo;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.rokid.stabledemo.core.GyroPredictor;

/** Live controls occupy the diagnostic chart area, leaving the projected text visible. */
final class PredictionTuner extends LinearLayout {
    interface Listener { void changed(int ms,GyroPredictor.Model model,double cap); }
    private final Listener listener;
    private final Runnable done;
    private final SeekBar slider;
    private final TextView title;
    private final Button modelButton,capButton,doneButton;
    private int horizon,selected;
    private GyroPredictor.Model model=GyroPredictor.Model.LATEST;
    private double cap=.75;
    private boolean configuring;

    PredictionTuner(Context context,float scale,Listener listener,Runnable done) {
        super(context); this.listener=listener; this.done=done;
        setOrientation(VERTICAL); setBackgroundColor(Color.BLACK); setClickable(true);
        title=new TextView(context); title.setGravity(Gravity.CENTER);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX,15*scale);
        addView(title,new LayoutParams(-1,Math.round(20*scale)));
        slider=new SeekBar(context); slider.setTag("gyro_horizon_slider"); slider.setMax(100);
        slider.setKeyProgressIncrement(1); slider.setFocusable(false); slider.setContentDescription("预测时长，0到100毫秒");
        slider.setProgressTintList(ColorStateList.valueOf(Color.GREEN)); slider.setThumbTintList(ColorStateList.valueOf(Color.GREEN));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(0xff006600));
        addView(slider,new LayoutParams(-1,Math.round(28*scale)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser) {
                if(!configuring && fromUser) { horizon=value; changed(); }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout row=new LinearLayout(context);
        addView(row,new LayoutParams(-1,Math.round(32*scale)));
        button(row,"−10",scale,() -> adjust(-10)); button(row,"+10",scale,() -> adjust(10));
        modelButton=button(row,"",scale,this::cycleModel); modelButton.setTag("gyro_model");
        capButton=button(row,"",scale,this::cycleCap); capButton.setTag("gyro_cap");
        doneButton=button(row,"完成",scale,done); doneButton.setTag("gyro_tuner_done");
        TextView guide=new TextView(context); guide.setGravity(Gravity.CENTER); guide.setTextColor(Color.GREEN);
        guide.setTextSize(TypedValue.COMPLEX_UNIT_PX,11*scale);
        guide.setText("单击换项 · 双击+5/切换 · 长按−5/完成 · 音量键±1");
        addView(guide,new LayoutParams(-1,Math.round(20*scale)));
        configure(0,model,cap);
    }
    private Button button(LinearLayout row,String text,float scale,Runnable action) {
        Button button=new Button(getContext()); button.setText(text); button.setAllCaps(false);
        button.setFocusable(false); button.setDefaultFocusHighlightEnabled(false);
        button.setTextColor(Color.GREEN); button.setTextSize(TypedValue.COMPLEX_UNIT_PX,14*scale);
        button.setMinHeight(0); button.setMinimumHeight(0); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(0,0,0,0); button.setBackgroundColor(Color.BLACK);
        button.setOnClickListener(v -> action.run()); row.addView(button,new LayoutParams(0,-1,1)); return button;
    }
    void configure(int ms,GyroPredictor.Model model,double cap) {
        horizon=ms; this.model=model; this.cap=cap; configuring=true; slider.setProgress(ms); configuring=false; refresh();
    }
    void adjust(int amount) { horizon=Math.max(0,Math.min(100,horizon+amount)); changed(); }
    void nextField() { selected=(selected+1)%4; refresh(); }
    void activate() {
        if(selected==0) { horizon=horizon>=100?0:Math.min(100,horizon+5); changed(); }
        else if(selected==1) cycleModel(); else if(selected==2) cycleCap(); else done.run();
    }
    void longPress() { if(selected==0) adjust(-5); else done.run(); }
    private void cycleModel() { model=model.next(); changed(); }
    private void cycleCap() { cap=cap==.75?3:cap==3?6:.75; changed(); }
    private void changed() { configure(horizon,model,cap); listener.changed(horizon,model,cap); }
    private void refresh() {
        title.setText("三轴 · 显示提前量 "+horizon+" ms · "+(selected==0?"[时长]":"时长"));
        title.setTextColor(Color.GREEN);
        String modelText=model==GyroPredictor.Model.LATEST?"1采样":model.window+"点试验",capText="限"+cap+"°";
        modelButton.setText(selected==1?"["+modelText+"]":modelText);
        capButton.setText(selected==2?"["+capText+"]":capText); doneButton.setText(selected==3?"[完成]":"完成");
        capButton.setContentDescription("预测修正上限"+cap+"度");
    }
}
