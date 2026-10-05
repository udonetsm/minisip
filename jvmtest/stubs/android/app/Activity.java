package android.app;

import android.content.Context;
import android.content.Intent;

public class Activity extends Context {
    public static int lastRequest = -1;

    public void startActivityForResult(Intent i, int code) { lastRequest = code; }
}
