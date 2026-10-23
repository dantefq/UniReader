package app.quietread;

import android.app.AlertDialog;
import android.content.Context;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/** Requests the IME only while the actual editor window is attached and focused. */
final class Keyboard {
    static void show(EditText field,AlertDialog dialog){
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        field.requestFocus();
        field.post(new Runnable(){int attempts;
            @Override public void run(){
                if(!dialog.isShowing()||!field.isAttachedToWindow())return;
                if(field.hasWindowFocus()&&((InputMethodManager)field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT))return;
                if(++attempts<5)field.postDelayed(this,100);
            }
        });
    }
}
