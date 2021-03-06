package im.tny.segvault.disturbances;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import im.tny.segvault.disturbances.model.Trip;
import im.tny.segvault.s2ls.Path;
import im.tny.segvault.subway.Network;
import im.tny.segvault.subway.Station;
import io.realm.Realm;

public class TripCorrectionActivity extends AppCompatActivity {

    private String networkId;
    private String tripId;
    private boolean isStandalone;

    MainService locService;
    boolean locBound = false;

    private Network network;

    private StationPickerView startPicker;
    private StationPickerView endPicker;
    private LinearLayout pathLayout;
    private Button saveButton;

    private Path originalPath;
    private Path newPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            networkId = getIntent().getStringExtra(EXTRA_NETWORK_ID);
            tripId = getIntent().getStringExtra(EXTRA_TRIP_ID);
            isStandalone = getIntent().getBooleanExtra(EXTRA_IS_STANDALONE, false);
        } else {
            networkId = savedInstanceState.getString(STATE_NETWORK_ID);
            tripId = savedInstanceState.getString(STATE_TRIP_ID);
            isStandalone = savedInstanceState.getBoolean(STATE_IS_STANDALONE, false);
        }
        Object conn = getLastCustomNonConfigurationInstance();
        if (conn != null) {
            // have the service connection survive through activity configuration changes
            // (e.g. screen orientation changes)
            mConnection = (LocServiceConnection) conn;
            locService = mConnection.getBinder().getService();
            locBound = true;
        } else if (!locBound) {
            startService(new Intent(this, MainService.class));
            getApplicationContext().bindService(new Intent(getApplicationContext(), MainService.class), mConnection, Context.BIND_AUTO_CREATE);
        }

        setContentView(R.layout.activity_trip_correction);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        if(isStandalone) {
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_close_white_24dp);
        }

        startPicker = (StationPickerView) findViewById(R.id.start_picker);
        endPicker = (StationPickerView) findViewById(R.id.end_picker);
        pathLayout = (LinearLayout) findViewById(R.id.path_layout);
        saveButton = (Button) findViewById(R.id.save_button);

        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean(STATE_START_FOCUSED, false)) {
                startPicker.focusOnEntry();
            }
            if (savedInstanceState.getBoolean(STATE_END_FOCUSED, false)) {
                endPicker.focusOnEntry();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.trip_correction, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                super.onBackPressed();
                return true;
            case R.id.menu_save:
                saveChanges();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void populateUI() {
        Realm realm = Realm.getDefaultInstance();
        Trip trip = realm.where(Trip.class).equalTo("id", tripId).findFirst();

        originalPath = trip.toConnectionPath(network);
        realm.close();

        if(!trip.canBeCorrected()) {
            finish();
        }

        List<Station> stations = new ArrayList<>(network.getStations());

        startPicker.setStations(stations);
        endPicker.setStations(stations);

        startPicker.setAllStationsSortStrategy(new StationPickerView.DistanceSortStrategy(network, originalPath.getStartVertex()));
        endPicker.setAllStationsSortStrategy(new StationPickerView.DistanceSortStrategy(network, originalPath.getEndVertex()));

        startPicker.setWeakSelection(originalPath.getStartVertex().getStation());
        endPicker.setWeakSelection(originalPath.getEndVertex().getStation());

        StationPickerView.OnStationSelectedListener onStationSelectedListener = new StationPickerView.OnStationSelectedListener() {
            @Override
            public void onStationSelected(Station station) {
                redrawPath();
            }
        };
        startPicker.setOnStationSelectedListener(onStationSelectedListener);
        endPicker.setOnStationSelectedListener(onStationSelectedListener);

        StationPickerView.OnSelectionLostListener onSelectionLostListener = new StationPickerView.OnSelectionLostListener() {
            @Override
            public void onSelectionLost() {
                redrawPath();
            }
        };
        startPicker.setOnSelectionLostListener(onSelectionLostListener);
        endPicker.setOnSelectionLostListener(onSelectionLostListener);

        redrawPath();

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveChanges();
            }
        });
    }

    private void redrawPath() {
        newPath = new Path(originalPath);
        boolean hasChanges = false;
        if (startPicker.getSelection() != null && !startPicker.getSelection().isAlwaysClosed() && startPicker.getSelection() != originalPath.getStartVertex().getStation()) {
            newPath.manualExtendStart(startPicker.getSelection().getStops().iterator().next());
            hasChanges = true;
        }
        if (endPicker.getSelection() != null && !endPicker.getSelection().isAlwaysClosed() && endPicker.getSelection() != originalPath.getEndVertex().getStation()) {
            newPath.manualExtendEnd(endPicker.getSelection().getStops().iterator().next());
            hasChanges = true;
        }

        TripFragment.populatePathView(this, getLayoutInflater(), network, newPath, pathLayout);

        if (hasChanges) {
            saveButton.setText(getString(R.string.act_trip_correction_save));
        } else {
            saveButton.setText(getString(R.string.act_trip_correction_correct));
        }
    }

    private void saveChanges() {
        Trip.persistConnectionPath(newPath, tripId);
        finish();
    }

    private LocServiceConnection mConnection = new LocServiceConnection();

    class LocServiceConnection implements ServiceConnection {
        MainService.LocalBinder binder;

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            binder = (MainService.LocalBinder) service;
            locService = binder.getService();
            locBound = true;

            network = locService.getNetwork(networkId);

            populateUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            locBound = false;
        }

        public MainService.LocalBinder getBinder() {
            return binder;
        }
    }

    public static final String STATE_TRIP_ID = "tripId";
    public static final String STATE_NETWORK_ID = "networkId";
    public static final String STATE_IS_STANDALONE = "standalone";
    public static final String STATE_START_FOCUSED = "startFocused";
    public static final String STATE_END_FOCUSED = "endFocused";

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putString(STATE_TRIP_ID, tripId);
        savedInstanceState.putString(STATE_NETWORK_ID, networkId);
        savedInstanceState.putBoolean(STATE_START_FOCUSED, startPicker.isFocused());
        savedInstanceState.putBoolean(STATE_END_FOCUSED, endPicker.isFocused());

        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);
    }

    public static final String EXTRA_TRIP_ID = "im.tny.segvault.disturbances.extra.TripCorrectionActivity.tripid";
    public static final String EXTRA_NETWORK_ID = "im.tny.segvault.disturbances.extra.TripCorrectionActivity.networkid";
    public static final String EXTRA_IS_STANDALONE = "im.tny.segvault.disturbances.extra.StationActivity.standalone";
}
