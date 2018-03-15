package com.example.trangdao.argis;

import android.app.ProgressDialog;
import android.content.Context;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

public class BaseActivity extends AppCompatActivity {

    private ProgressDialog mProgressDialog;

    protected boolean isLocationProviderEnable() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                || lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    protected boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && cm.getActiveNetworkInfo() != null;
    }



    protected void showProgressDialog() {
        if (mProgressDialog == null) {
            mProgressDialog = new ProgressDialog(this);
            mProgressDialog.setMessage("Loading...");
        }
        mProgressDialog.show();
    }

    protected void hideProgressDialog() {
        if (mProgressDialog != null) {
            mProgressDialog.hide();
        }
    }
}
