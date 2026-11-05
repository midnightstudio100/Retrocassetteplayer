package com.muse.retrocassette;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
public class SettingsActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SharedPreferences prefs = getSharedPreferences("muse_prefs", 0);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        layout.setBackgroundColor(Color.parseColor("#1A1A1A"));
        scroll.addView(layout);
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(28);
        title.setTextColor(Color.parseColor("#FFD700"));
        layout.addView(title);
        final Switch btPause = new Switch(this);
        btPause.setText("Pause when Bluetooth disconnects");
        btPause.setTextColor(Color.WHITE);
        btPause.setChecked(prefs.getBoolean("pause_on_bt_disconnect", false));
        btPause.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                prefs.edit().putBoolean("pause_on_bt_disconnect", on).apply();
                Toast.makeText(SettingsActivity.this, on ? "Bluetooth pause ON" : "Bluetooth pause OFF", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(btPause);
        TextView note = new TextView(this);
        note.setText("When this is on, music and radio pause automatically if your Bluetooth headphones disconnect. It works right away, no reopen needed.");
        note.setTextColor(Color.parseColor("#CCCCCC"));
        note.setPadding(0, 8, 0, 24);
        layout.addView(note);
        Button bt = new Button(this);
        bt.setText("Bluetooth - Connect headphones");
        bt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); }
                catch (Exception e) { Toast.makeText(SettingsActivity.this, "Open Settings > Connections > Bluetooth", Toast.LENGTH_LONG).show(); }
            }
        });
        layout.addView(bt);
        Button back = new Button(this);
        back.setText("Back to Radio");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        layout.addView(back);
        setContentView(scroll);
    }
}
