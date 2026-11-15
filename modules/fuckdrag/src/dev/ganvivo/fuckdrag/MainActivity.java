package dev.ganvivo.fuckdrag;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("FuckDrag 0.1.0\n\n"
                + "Only hooks com.vivo.globaldragdrop drag gates:\n"
                + "vb.p.h -> true\n"
                + "bb.a.a -> non-null placeholder for yb.f\n\n"
                + "Scope: com.vivo.globaldragdrop only.\n"
                + "No system_server / launcher / recents / split / share hooks.");
        text.setTextSize(16);
        text.setGravity(Gravity.CENTER);
        text.setPadding(48, 48, 48, 48);
        setContentView(text);
    }
}
