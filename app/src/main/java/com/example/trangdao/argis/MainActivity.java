package com.example.trangdao.argis;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.database.MatrixCursor;
import android.location.Location;
import android.location.LocationListener;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.SearchView;
import android.widget.SimpleCursorAdapter;

import com.esri.android.map.LocationDisplayManager;
import com.esri.android.map.MapView;
import com.esri.android.map.event.OnStatusChangedListener;
import com.esri.android.toolkit.map.MapViewHelper;
import com.esri.core.geometry.Envelope;
import com.esri.core.geometry.GeometryEngine;
import com.esri.core.geometry.LinearUnit;
import com.esri.core.geometry.Point;
import com.esri.core.geometry.SpatialReference;
import com.esri.core.geometry.Unit;
import com.esri.core.map.CallbackListener;
import com.esri.core.tasks.geocode.Locator;
import com.esri.core.tasks.geocode.LocatorFindParameters;
import com.esri.core.tasks.geocode.LocatorGeocodeResult;
import com.esri.core.tasks.geocode.LocatorSuggestionParameters;
import com.esri.core.tasks.geocode.LocatorSuggestionResult;

import java.util.List;
import java.util.ArrayList;

public class MainActivity extends BaseActivity implements OnStatusChangedListener, LocationListener {
    private static final String COLUMN_NAME_ADDRESS = "address";
    private static final String COLUMN_NAME_X = "x";
    private static final String COLUMN_NAME_Y = "y";
    private static final String LOCATION_TITLE = "Location";
    private static final String FIND_PLACE = "Find";
    private static final String SUGGEST_PLACE = "Suggest";

    private static final int REQUEST_CODE_LOCATION_PERMISSION = 100;
    private static final String TAG = MainActivity.class.getSimpleName();

    private boolean suggestClickFlag = false;
    private boolean searchClickFlag = false;

    private MapView mMapView;
    private String mMapViewState;
    private MapViewHelper mMapViewHelper;
    private Locator mLocator;

    private SearchView mSearchView;
    private MatrixCursor mSuggestionCursor;
    private LocatorSuggestionParameters suggestParams;
    private LocatorFindParameters findParams;
    private SpatialReference mapSpatialReference;
    private List<LocatorSuggestionResult> suggestionsList = new ArrayList<>();

    private LocationDisplayManager mLocationDisplayManager;
    private boolean isFirstLoad = false;
    private Location lastKnowLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!isNetworkAvailable()) {
            new AlertDialog.Builder(this)
                    .setTitle("No internet connection")
                    .setMessage("Please enable internet connection to load data")
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .create().show();
        }

        initMapView();
        initSearchView();


        findViewById(R.id.image_current_location).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.e(TAG, "onClick locate me");
                if (!hasLocationPermission()) {
                    requestLocationPermission();
                    return;
                }

                if (lastKnowLocation != null) {
                    showLocationOnMap(lastKnowLocation);
                }
            }
        });
    }

    private void initMapView() {
        mMapView = findViewById(R.id.map);
        mMapViewHelper = new MapViewHelper(mMapView);
        mLocator = Locator.createOnlineLocator();

        mMapView.setEsriLogoVisible(false);
        mMapView.enableWrapAround(true);
        mMapView.setOnStatusChangedListener(this);
    }

    private void initSearchView() {
        mSearchView = findViewById(R.id.search_view);
        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {

            @Override
            public boolean onQueryTextSubmit(String query) {
                if (!suggestClickFlag && !searchClickFlag) {
                    searchClickFlag = true;
                    onSearchButtonClicked(query);
                    mSearchView.clearFocus();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onQueryTextChange(final String newText) {
                if (mLocator == null)
                    return false;

                getSuggestions(newText);
                return true;
            }
        });

        mSearchView.setOnSuggestionListener(new SearchView.OnSuggestionListener() {

            @Override
            public boolean onSuggestionSelect(int position) {
                return false;
            }

            @Override
            public boolean onSuggestionClick(int position) {
                MatrixCursor cursor = (MatrixCursor) mSearchView.getSuggestionsAdapter().getItem(position);
                int indexColumnSuggestion = cursor.getColumnIndex(COLUMN_NAME_ADDRESS);
                final String address = cursor.getString(indexColumnSuggestion);
                suggestClickFlag = true;

                new FindLocationTask(address).execute(address);
                cursor.close();
                return true;
            }
        });
    }

    private void initSuggestionCursor() {
        String[] cols = new String[]{BaseColumns._ID, COLUMN_NAME_ADDRESS, COLUMN_NAME_X, COLUMN_NAME_Y};
        mSuggestionCursor = new MatrixCursor(cols);
    }

    private void applySuggestionCursor() {
        String[] cols = new String[]{COLUMN_NAME_ADDRESS};
        int[] to = new int[]{R.id.suggestion_item_address};

        SimpleCursorAdapter mSuggestionAdapter = new SimpleCursorAdapter(mMapView.getContext(), R.layout.suggestion_item, mSuggestionCursor, cols, to, 0);
        mSearchView.setSuggestionsAdapter(mSuggestionAdapter);
        mSuggestionAdapter.notifyDataSetChanged();
    }

    public void onSearchButtonClicked(String address) {
        hideKeyboard();
        mMapViewHelper.removeAllGraphics();
        executeLocatorTask(address);
    }

    private void executeLocatorTask(String address) {
        locatorParams(FIND_PLACE, address);

        LocatorAsyncTask locatorTask = new LocatorAsyncTask();
        locatorTask.execute(findParams);
    }

    @Override
    public void onStatusChanged(Object source, STATUS status) {
        if (source == mMapView && status == STATUS.INITIALIZED) {
            mapSpatialReference = mMapView.getSpatialReference();

            if (mMapViewState != null) {
                mMapView.restoreState(mMapViewState);
            }

            if (hasLocationPermission()) {
                startLocationDisplayManager();
            }
        }
    }

    private void startLocationDisplayManager() {
        if (mLocationDisplayManager == null) {
            mLocationDisplayManager = mMapView.getLocationDisplayManager();
            mLocationDisplayManager.setAutoPanMode(LocationDisplayManager.AutoPanMode.LOCATION);
            mLocationDisplayManager.setLocationListener(this);
            mLocationDisplayManager.start();
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        lastKnowLocation = loc;
        if (!isFirstLoad) {
            isFirstLoad = true;
            showLocationOnMap(loc);
        }
    }


    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }

    @Override
    protected void onPause() {
        super.onPause();

        mMapViewState = mMapView.retainState();
        mMapView.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMapView.unpause();
        if (mMapViewState != null) {
            mMapView.restoreState(mMapViewState);

        }
    }

    protected void getSuggestions(String suggestText) {
        final CallbackListener<List<LocatorSuggestionResult>> suggestCallback = new CallbackListener<List<LocatorSuggestionResult>>() {
            @Override
            public void onCallback(List<LocatorSuggestionResult> locatorSuggestionResults) {
                final List<LocatorSuggestionResult> locSuggestionResults = locatorSuggestionResults;
                if (locatorSuggestionResults == null)
                    return;
                suggestionsList = new ArrayList<>();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int key = 0;
                        if (locSuggestionResults.size() > 0) {
                            initSuggestionCursor();
                            for (final LocatorSuggestionResult result : locSuggestionResults) {
                                suggestionsList.add(result);
                                mSuggestionCursor.addRow(new Object[]{key++, result.getText(), "0", "0"});
                            }
                            applySuggestionCursor();
                        }
                    }
                });

            }


            @Override
            public void onError(Throwable throwable) {
                Log.e(TAG, throwable.getMessage());
            }
        };

        try {
            locatorParams(SUGGEST_PLACE, suggestText);
            mLocator.suggest(suggestParams, suggestCallback);

        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    /**
     * Initialize the LocatorSuggestionParameters or LocatorFindParameters
     *
     * @param query The string for which the locator parameters are to be initialized
     */
    protected void locatorParams(String TYPE, String query) {
        if (TYPE.contentEquals(SUGGEST_PLACE)) {
            suggestParams = new LocatorSuggestionParameters(query);
            suggestParams.setLocation(mMapView.getCenter(), mMapView.getSpatialReference());
            suggestParams.setDistance(500.0);
        } else if (TYPE.contentEquals(FIND_PLACE)) {
            findParams = new LocatorFindParameters(query);
            findParams.setLocation(mMapView.getCenter(), mMapView.getSpatialReference());
            findParams.setDistance(500.0);
        }


    }

    private void showLocationOnMap(Location loc) {
        Point wgspoint = new Point(loc.getLongitude(), loc.getLatitude());
        Point mapPoint = (Point) GeometryEngine
                .project(wgspoint,
                        SpatialReference.create(4326),
                        mMapView.getSpatialReference());

        Unit mapUnit = mMapView.getSpatialReference()
                .getUnit();

        double zoomWidth = Unit.convertUnits(
                1000,
                Unit.create(LinearUnit.Code.METER),
                mapUnit);

        Envelope zoomExtent = new Envelope(mapPoint,
                zoomWidth, zoomWidth);
        mMapView.setExtent(zoomExtent);
    }

    private void displaySearchResult(double x, double y, String address) {
        mMapViewHelper.addMarkerGraphic(y, x, LOCATION_TITLE, address,
                android.R.drawable.ic_menu_myplaces, null, false, 1);
        mMapView.centerAndZoom(y, x, 17);
        mSearchView.setQuery(address, true);
        searchClickFlag = false;
        suggestClickFlag = false;

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_LOCATION_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationDisplayManager();
            }

        }
    }

    private void requestLocationPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)) {
            new AlertDialog.Builder(this)
                    .setTitle("Permission denied")
                    .setMessage("The app need location permission to get current location. Please enable.")
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            ActivityCompat.requestPermissions(MainActivity.this,
                                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                    REQUEST_CODE_LOCATION_PERMISSION);
                        }
                    })
                    .create()
                    .show();

        } else {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_CODE_LOCATION_PERMISSION);
        }

    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private class LocatorAsyncTask extends
            AsyncTask<LocatorFindParameters, Void, List<LocatorGeocodeResult>> {

        @Override
        protected void onPreExecute() {
            showProgressDialog();
        }

        @Override
        protected List<LocatorGeocodeResult> doInBackground(
                LocatorFindParameters... params) {

            List<LocatorGeocodeResult> results = null;
            try {
                Locator locator = Locator.createOnlineLocator();
                results = locator.find(params[0]);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage(), e);
            }
            return results;
        }

        @Override
        protected void onPostExecute(List<LocatorGeocodeResult> result) {
            hideProgressDialog();
            if (result.size() > 0) {
                LocatorGeocodeResult geocodeResult = result.get(0);
                Point resultPoint = geocodeResult.getLocation();

                displaySearchResult(resultPoint.getX(), resultPoint.getY(), geocodeResult.getAddress());
                hideKeyboard();
            }
        }

    }

    private class FindLocationTask extends AsyncTask<String, Void, Point> {
        private Point resultPoint = null;
        private String resultAddress;
        private Point temp = null;

        FindLocationTask(String address) {
            resultAddress = address;
        }

        @Override
        protected Point doInBackground(String... params) {
            for (LocatorSuggestionResult result : suggestionsList) {
                if (resultAddress.matches(result.getText())) {
                    try {
                        temp = ((mLocator.find(result, 2, null, mapSpatialReference)).get(0)).getLocation();
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage());
                    }
                }
            }

            resultPoint = (Point) GeometryEngine.project(temp, mapSpatialReference, SpatialReference.create(4326));
            return resultPoint;
        }

        @Override
        protected void onPreExecute() {
            showProgressDialog();
        }

        @Override
        protected void onPostExecute(Point resultPoint) {
            hideProgressDialog();
            if (resultPoint != null) {
                displaySearchResult(resultPoint.getX(), resultPoint.getY(), resultAddress);
                hideKeyboard();
            }
        }
    }

    protected void hideKeyboard() {
        mSearchView.clearFocus();
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputManager != null) {
            inputManager.hideSoftInputFromWindow(mSearchView.getWindowToken(), 0);
        }
    }

}
