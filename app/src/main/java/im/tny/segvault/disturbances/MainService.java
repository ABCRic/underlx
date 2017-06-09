package im.tny.segvault.disturbances;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.evernote.android.job.Job;
import com.evernote.android.job.JobCreator;
import com.evernote.android.job.JobRequest;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import im.tny.segvault.disturbances.exception.APIException;
import im.tny.segvault.disturbances.exception.CacheException;
import im.tny.segvault.disturbances.model.RStation;
import im.tny.segvault.disturbances.model.StationUse;
import im.tny.segvault.disturbances.model.Trip;
import im.tny.segvault.s2ls.InNetworkState;
import im.tny.segvault.s2ls.NearNetworkState;
import im.tny.segvault.s2ls.OffNetworkState;
import im.tny.segvault.s2ls.S2LS;
import im.tny.segvault.s2ls.State;
import im.tny.segvault.s2ls.wifi.BSSID;
import im.tny.segvault.s2ls.wifi.WiFiLocator;
import im.tny.segvault.subway.Connection;
import im.tny.segvault.subway.Lobby;
import im.tny.segvault.subway.Station;
import im.tny.segvault.subway.Stop;
import im.tny.segvault.subway.Transfer;
import im.tny.segvault.subway.Line;
import im.tny.segvault.subway.Network;
import im.tny.segvault.subway.Zone;
import io.realm.Realm;
import io.realm.RealmList;

public class MainService extends Service {
    public static final String PRIMARY_NETWORK_ID = "pt-ml";
    private API api;
    private ConnectionWeighter cweighter = new ConnectionWeighter(this);
    private WiFiChecker wfc;

    private final Object lock = new Object();
    private Map<String, Network> networks = new HashMap<>();
    private HashMap<String, LineStatus> lineStatuses = new HashMap<>();
    private Map<String, S2LS> locServices = new HashMap<>();

    private Date creationDate = null;

    // Binder given to clients
    private final IBinder mBinder = new LocalBinder();

    private Handler stateTickHandler = null;

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        MainService getService() {
            // Return this instance of MainService so clients can call public methods
            return MainService.this;
        }
    }

    public MainService() {

    }

    private void putNetwork(Network net) {
        synchronized (lock) {
            // create Realm stations for the network if they don't exist already
            Realm realm = Realm.getDefaultInstance();
            realm.beginTransaction();
            for (Station s : net.getStations()) {
                if (realm.where(RStation.class).equalTo("id", s.getId()).count() == 0) {
                    RStation rs = new RStation();
                    rs.setStop(s);
                    rs.setNetwork(net.getId());
                    realm.copyToRealm(rs);
                }
            }
            realm.commitTransaction();

            net.setEdgeWeighter(cweighter);
            networks.put(net.getId(), net);
            S2LS loc = new S2LS(net, new S2SLChangeListener());
            locServices.put(net.getId(), loc);
            WiFiLocator wl = new WiFiLocator(net);
            wfc.setLocatorForNetwork(net, wl);
            loc.addNetworkDetector(wl);
            loc.addProximityDetector(wl);
            loc.addLocator(wl);
        }
    }

    @Override
    public void onCreate() {
        creationDate = new Date();
        stateTickHandler = new Handler();
        PreferenceManager.setDefaultValues(this.getApplicationContext(), R.xml.notif_settings, false);
        api = API.getInstance();
        wfc = new WiFiChecker(this, (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE));
        wfc.setScanInterval(10000);
        checkForTopologyUpdates();

        SharedPreferences sharedPref = getSharedPreferences("settings", MODE_PRIVATE);
        sharedPref.registerOnSharedPreferenceChangeListener(generalPrefsListener);
    }

    @Override
    public void onDestroy() {
        wfc.stopScanning();
        SharedPreferences sharedPref = getSharedPreferences("settings", MODE_PRIVATE);
        sharedPref.unregisterOnSharedPreferenceChangeListener(generalPrefsListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (networks.size() == 0) {
            loadNetworks();
        }
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_CHECK_TOPOLOGY_UPDATES:
                    Log.d("MainService", "onStartCommand CheckUpdates");
                    if (new Date().getTime() - creationDate.getTime() < TimeUnit.SECONDS.toMillis(10)) {
                        // service started less than 10 seconds ago, no need to check again
                        break;
                    }
                    checkForTopologyUpdates(true);
                    Log.d("MainService", "onStartCommand updates checked");
                    break;
                case ACTION_DISTURBANCE_NOTIFICATION:
                    final String network = intent.getStringExtra(EXTRA_DISTURBANCE_NETWORK);
                    final String line = intent.getStringExtra(EXTRA_DISTURBANCE_LINE);
                    final String id = intent.getStringExtra(EXTRA_DISTURBANCE_ID);
                    final String status = intent.getStringExtra(EXTRA_DISTURBANCE_STATUS);
                    final boolean downtime = intent.getBooleanExtra(EXTRA_DISTURBANCE_DOWNTIME, false);
                    final long msgtime = intent.getLongExtra(EXTRA_DISTURBANCE_MSGTIME, 0);
                    handleDisturbanceNotification(network, line, id, status, downtime, msgtime);
                    break;
            }
        }

        FirebaseMessaging.getInstance().subscribeToTopic("disturbances");

        UpdateTopologyJob.schedule();
        return Service.START_STICKY;
    }

    private void loadNetworks() {
        synchronized (lock) {
            try {
                Network net = TopologyCache.loadNetwork(this, PRIMARY_NETWORK_ID);
                /*for (Line l : net.getLines()) {
                    Log.d("UpdateTopologyTask", "Line: " + l.getName());
                    for (Stop s : l.vertexSet()) {
                        Log.d("UpdateTopologyTask", s.toString());
                    }
                }*/
                Log.d("loadNetworks", "INTERCHANGES");
                for (Connection c : net.edgeSet()) {
                    if (c instanceof Transfer) {
                        Log.d("loadNetworks", " INTERCHANGE");
                        for (Stop s : c.getStops()) {
                            Log.d("loadNetworks", String.format("  %s", s.toString()));
                        }
                        for (Line l : ((Transfer) c).getLines()) {
                            Log.d("loadNetworks", String.format("  %s", l.toString()));
                        }
                    }
                }

                putNetwork(net);

                S2LS loc = locServices.get(PRIMARY_NETWORK_ID);
                Log.d("loadNetworks", String.format("In network? %b", loc.inNetwork()));
                Log.d("loadNetworks", String.format("Near network? %b", loc.nearNetwork()));
                Zone z = loc.getLocation();
                for (Stop s : z.vertexSet()) {
                    Log.d("loadNetworks", String.format("May be in station %s", s));
                }
            } catch (CacheException e) {
                // cache invalid, attempt to reload topology
                updateTopology();
            }
        }
    }

    public void updateTopology() {
        cancelTopologyUpdate();
        currentUpdateTopologyTask = new UpdateTopologyTask();
        currentUpdateTopologyTask.executeOnExecutor(LARGE_STACK_THREAD_POOL_EXECUTOR, PRIMARY_NETWORK_ID);
    }

    public void updateTopology(String... network_ids) {
        cancelTopologyUpdate();
        currentUpdateTopologyTask = new UpdateTopologyTask();
        currentUpdateTopologyTask.executeOnExecutor(LARGE_STACK_THREAD_POOL_EXECUTOR, network_ids);
    }

    public void cancelTopologyUpdate() {
        if (currentUpdateTopologyTask != null) {
            currentUpdateTopologyTask.cancel(true);
        }
    }

    public void checkForTopologyUpdates() {
        currentCheckTopologyUpdatesTask = new CheckTopologyUpdatesTask();
        currentCheckTopologyUpdatesTask.execute(Connectivity.isConnectedWifi(this));
    }

    public void checkForTopologyUpdates(boolean autoUpdate) {
        currentCheckTopologyUpdatesTask = new CheckTopologyUpdatesTask();
        currentCheckTopologyUpdatesTask.execute(autoUpdate);
    }

    public Collection<Network> getNetworks() {
        synchronized (lock) {
            return networks.values();
        }
    }

    public Network getNetwork(String id) {
        synchronized (lock) {
            return networks.get(id);
        }
    }

    public List<Stop> getAllStations() {
        List<Stop> stops = new ArrayList<>();
        synchronized (lock) {
            for (Network n : networks.values()) {
                stops.addAll(n.vertexSet());
            }
        }
        return stops;
    }

    public List<Line> getAllLines() {
        List<Line> lines = new ArrayList<>();
        synchronized (lock) {
            for (Network n : networks.values()) {
                lines.addAll(n.getLines());
            }
        }
        return lines;
    }

    public static class LineStatus implements Serializable {
        private final String id;
        public final boolean down;
        public final Date downSince;
        public final Date updated;
        public transient Line line;

        public LineStatus(Line line) {
            this.id = line.getId();
            this.line = line;
            this.down = false;
            this.downSince = new Date();
            this.updated = new Date();
        }

        public LineStatus(Line line, Date downSince) {
            this.id = line.getId();
            this.line = line;
            this.down = true;
            this.downSince = downSince;
            this.updated = new Date();
        }
    }

    private class UpdateLineStatusTask extends AsyncTask<Void, Integer, Boolean> {
        private HashMap<String, LineStatus> statuses = new HashMap<>();

        @Override
        protected void onPreExecute() {
            Intent intent = new Intent(ACTION_LINE_STATUS_UPDATE_STARTED);
            LocalBroadcastManager bm = LocalBroadcastManager.getInstance(MainService.this);
            bm.sendBroadcast(intent);
        }

        protected Boolean doInBackground(Void... v) {
            if (!Connectivity.isConnected(MainService.this)) {
                return false;
            }
            List<Line> lines = new LinkedList<>();
            for (Network n : getNetworks()) {
                lines.addAll(n.getLines());
            }
            try {
                List<API.Disturbance> disturbances = API.getInstance().getOngoingDisturbances();
                for (Line l : lines) {
                    boolean foundDisturbance = false;
                    for (API.Disturbance d : disturbances) {
                        if (d.line.equals(l.getId()) && !d.ended) {
                            foundDisturbance = true;
                            statuses.put(l.getId(), new LineStatus(l, new Date(d.startTime[0] * 1000)));
                            break;
                        }
                    }
                    if (!foundDisturbance) {
                        statuses.put(l.getId(), new LineStatus(l));
                    }
                }
            } catch (APIException e) {
                return false;
            }
            cacheLineStatus(statuses);
            synchronized (lock) {
                lineStatuses.clear();
                lineStatuses.putAll(statuses);
            }
            return true;
        }

        protected void onProgressUpdate(Integer... progress) {

        }

        protected void onPostExecute(Boolean result) {
            Intent intent;
            if (result) {
                intent = new Intent(ACTION_LINE_STATUS_UPDATE_SUCCESS);
            } else {
                intent = new Intent(ACTION_LINE_STATUS_UPDATE_FAILED);
            }
            LocalBroadcastManager bm = LocalBroadcastManager.getInstance(MainService.this);
            bm.sendBroadcast(intent);
        }
    }

    private final String LINE_STATUS_CACHE_FILENAME = "LineStatusCache";

    private void cacheLineStatus(HashMap<String, LineStatus> info) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(getCacheDir(), LINE_STATUS_CACHE_FILENAME));
            ObjectOutputStream os = new ObjectOutputStream(fos);
            os.writeObject(info);
            os.close();
            fos.close();
        } catch (Exception e) {
            // oh well, we'll have to do without cache
            // caching is best-effort
            e.printStackTrace();
        }
    }

    private HashMap<String, LineStatus> readLineStatus() {
        HashMap<String, LineStatus> info = null;
        try {
            FileInputStream fis = new FileInputStream(new File(getCacheDir(), LINE_STATUS_CACHE_FILENAME));
            ObjectInputStream is = new ObjectInputStream(fis);
            info = (HashMap) is.readObject();
            is.close();
            fis.close();

            Collection<Network> networks = getNetworks();
            Map<String, Line> lines = new HashMap<>();
            for (Line l : getAllLines()) {
                lines.put(l.getId(), l);
            }
            for (LineStatus s : info.values()) {
                s.line = lines.get(s.id);
            }
        } catch (Exception e) {
            // oh well, we'll have to do without cache
            // caching is best-effort
            e.printStackTrace();
        }
        return info;
    }

    public Map<String, LineStatus> getLineStatus() {
        HashMap<String, LineStatus> statuses = new HashMap<>();
        synchronized (lock) {
            if (lineStatuses.isEmpty()) {
                lineStatuses = readLineStatus();
                if (lineStatuses == null) {
                    lineStatuses = new HashMap<>();
                }
            }
            statuses.putAll(lineStatuses);
        }
        return statuses;
    }

    public LineStatus getLineStatus(String id) {
        synchronized (lock) {
            if (lineStatuses.isEmpty()) {
                lineStatuses = readLineStatus();
                if (lineStatuses == null) {
                    lineStatuses = new HashMap<>();
                }
            }
            return lineStatuses.get(id);
        }
    }

    public void updateLineStatus() {
        new UpdateLineStatusTask().execute();
    }

    // DEBUG:
    protected String dumpDebugInfo() {
        String s = "Service created on " + creationDate.toString();
        s += (wfc.isScanning() ? String.format(". WFC scanning every %d s", wfc.getScanInterval() / 1000) : ". WFC not scanning") + "\n";
        for (Network n : getNetworks()) {
            S2LS loc;
            synchronized (lock) {
                loc = locServices.get(n.getId());
            }
            s += "Network " + n.getName() + "\n";
            s += "State machine: " + loc.getState().getType().toString() + "\n";
            s += String.format("\tIn network? %b\n\tNear network? %b\n", loc.inNetwork(), loc.nearNetwork());
            if (loc.getState() instanceof InNetworkState) {
                InNetworkState ins = (InNetworkState) loc.getState();
                s += "\tPossible stops:\n";
                for (Stop stop : loc.getLocation().vertexSet()) {
                    s += String.format("\t\t%s (%s)\n", stop.getStation().getName(), stop.getLine().getName());
                }
                s += "\tPath:\n";
                for (Connection c : ins.getPath().getEdgeList()) {
                    s += String.format("\t\t%s -> %s\n", c.getSource().toString(), c.getTarget().toString());
                }
            }
        }
        return s;
    }

    // END OF DEBUG

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private UpdateTopologyTask currentUpdateTopologyTask = null;

    private class UpdateTopologyTask extends AsyncTask<String, Integer, Boolean> {
        protected Boolean doInBackground(String... networkIds) {
            int net_count = networkIds.length;
            publishProgress(0);
            try {
                for (int cur_net = 0; cur_net < net_count; cur_net++) {
                    API.Network n = api.getNetwork(networkIds[cur_net]);

                    float netPart = (float) (cur_net + 1) / (float) net_count;
                    Log.d("UpdateTopologyTask", "Updating network " + n.id);
                    Network net = new Network(n.id, n.name, n.typCars, n.holidays, n.openTime * 1000, n.duration * 1000, n.timezone, n.newsURL);

                    Map<String, API.Station> apiStations = new HashMap<>();
                    for (API.Station s : api.getStations()) {
                        if (s.network.equals(n.id)) {
                            apiStations.put(s.id, s);
                        }
                    }

                    publishProgress(15);

                    Map<String, API.Lobby> apiLobbies = new HashMap<>();
                    for (API.Lobby l : api.getLobbies()) {
                        if (l.network.equals(n.id)) {
                            apiLobbies.put(l.id, l);
                        }
                    }

                    publishProgress(40);

                    int line_count = n.lines.size();
                    int cur_line = 0;
                    for (String lineid : n.lines) {
                        Log.d("UpdateTopologyTask", " Line: " + lineid);
                        API.Line l = api.getLine(lineid);
                        Line line = new Line(net, new HashSet<Stop>(), l.id, l.name, l.typCars);
                        line.setColor(Color.parseColor("#" + l.color));
                        for (String sid : l.stations) {
                            Log.d("UpdateTopologyTask", "  Stop: " + sid);
                            API.Station s = apiStations.get(sid);
                            Station station = net.getStation(s.id);
                            if (station == null) {
                                station = new Station(net, s.id, s.name,
                                        new Station.Features(s.features.lift, s.features.bus, s.features.boat, s.features.train, s.features.airport));

                                // Lobbies
                                for (String id : s.lobbies) {
                                    API.Lobby alobby = apiLobbies.get(id);
                                    Lobby lobby = new Lobby(alobby.id, alobby.name);
                                    for (API.Exit aexit : alobby.exits) {
                                        Lobby.Exit exit = new Lobby.Exit(aexit.id, aexit.worldCoord, aexit.streets);
                                        lobby.addExit(exit);
                                    }
                                    for (API.Schedule asched : alobby.schedule) {
                                        Lobby.Schedule sched = new Lobby.Schedule(
                                                asched.holiday, asched.day, asched.open, asched.openTime * 1000, asched.duration * 1000);
                                        lobby.addSchedule(sched);
                                    }
                                    station.addLobby(lobby);
                                }
                            }
                            Stop stop = new Stop(station, line);
                            line.addVertex(stop);
                            station.addVertex(stop);

                            // WiFi APs
                            for (API.WiFiAP w : s.wiFiAPs) {
                                // take line affinity into account
                                if (w.line.equals(line.getId())) {
                                    WiFiChecker.addBSSIDforStop(stop, new BSSID(w.bssid));
                                }
                            }
                        }
                        net.addLine(line);
                        cur_line++;
                        publishProgress(40 + (int) (((cur_line / (float) (line_count)) * netPart) * 20));
                        if (cur_line < line_count) {
                            cur_line++;
                        }
                        if (isCancelled()) break;
                    }
                    if (isCancelled()) break;

                    // Connections are within stations in the same line
                    for (API.Connection c : api.getConnections()) {
                        Set<Stop> sFrom = net.getStation(c.from).vertexSet();
                        Set<Stop> sTo = net.getStation(c.to).vertexSet();
                        Stop from = null, to = null;
                        for (Stop s : sFrom) {
                            for (Stop s2 : sTo) {
                                if (s.getLine().getId().equals(s2.getLine().getId())) {
                                    from = s;
                                    to = s2;
                                }
                            }
                        }
                        if (from != null && to != null) {
                            Connection newConnection = net.addEdge(from, to);
                            from.getLine().addEdge(from, to);
                            net.setEdgeWeight(newConnection, c.typS + 1); // TODO remove constant
                        }
                    }

                    publishProgress(80);

                    for (API.Transfer t : api.getTransfers()) {
                        Transfer newTransfer = new Transfer();
                        // find stations with the right IDs for each line
                        Station station = net.getStation(t.station);
                        for (Stop from : station.vertexSet()) {
                            for (Stop to : station.vertexSet()) {
                                if (from.getLine().getId().equals(t.from) && to.getLine().getId().equals(t.to)) {
                                    net.addEdge(from, to, newTransfer);
                                    net.setEdgeWeight(newTransfer, t.typS + 3); // TODO remove constant
                                }
                            }

                        }
                    }

                    publishProgress(100);

                    API.DatasetInfo info = api.getDatasetInfo(net.getId());
                    net.setDatasetAuthors(info.authors);
                    net.setDatasetVersion(info.version);
                    synchronized (lock) {
                        lineStatuses.clear();
                    }
                    putNetwork(net);
                    TopologyCache.saveNetwork(MainService.this, net);
                    if (isCancelled()) break;
                }
            } catch (APIException e) {
                return false;
            } catch (CacheException e) {
                return false;
            }
            return true;
        }

        protected void onProgressUpdate(Integer... progress) {
            Intent intent = new Intent(ACTION_UPDATE_TOPOLOGY_PROGRESS);
            intent.putExtra(EXTRA_UPDATE_TOPOLOGY_PROGRESS, progress[0]);
            LocalBroadcastManager bm = LocalBroadcastManager.getInstance(MainService.this);
            bm.sendBroadcast(intent);
        }

        protected void onPostExecute(Boolean result) {
            Log.d("UpdateTopologyTask", result.toString());
            currentUpdateTopologyTask = null;
            Intent intent = new Intent(ACTION_UPDATE_TOPOLOGY_FINISHED);
            intent.putExtra(EXTRA_UPDATE_TOPOLOGY_FINISHED, result);
            LocalBroadcastManager bm = LocalBroadcastManager.getInstance(MainService.this);
            bm.sendBroadcast(intent);
        }

        @Override
        protected void onCancelled() {
            Log.d("UpdateTopologyTask", "onCancelled");
            currentUpdateTopologyTask = null;
            Intent intent = new Intent(ACTION_UPDATE_TOPOLOGY_CANCELLED);
            LocalBroadcastManager bm = LocalBroadcastManager.getInstance(MainService.this);
            bm.sendBroadcast(intent);
        }
    }

    private CheckTopologyUpdatesTask currentCheckTopologyUpdatesTask = null;

    private class CheckTopologyUpdatesTask extends AsyncTask<Boolean, Integer, Boolean> {
        // returns true if there are updates, false if not
        private boolean autoUpdate;

        protected Boolean doInBackground(Boolean... autoUpdate) {
            this.autoUpdate = autoUpdate[0];
            Log.d("MainService", "CheckTopologyUpdatesTask");
            try {
                synchronized (lock) {
                    for (API.DatasetInfo di : api.getDatasetInfos()) {
                        if (!networks.containsKey(di.network)) {
                            return true;
                        }
                        Network net = networks.get(di.network);
                        if (!di.version.equals(net.getDatasetVersion())) {
                            return true;
                        }
                    }
                }
                return false;
            } catch (APIException e) {
                return false;
            }
        }

        protected void onProgressUpdate(Integer... progress) {

        }

        protected void onPostExecute(Boolean result) {
            if (result) {
                if (autoUpdate) {
                    updateTopology();
                } else {
                    Intent intent = new Intent(ACTION_TOPOLOGY_UPDATE_AVAILABLE);
                    LocalBroadcastManager bm = LocalBroadcastManager.getInstance(MainService.this);
                    bm.sendBroadcast(intent);
                }
            }
            currentCheckTopologyUpdatesTask = null;
        }
    }

    public static final String ACTION_UPDATE_TOPOLOGY_PROGRESS = "im.tny.segvault.disturbances.action.topology.update.progress";
    public static final String EXTRA_UPDATE_TOPOLOGY_PROGRESS = "im.tny.segvault.disturbances.extra.topology.update.progress";
    public static final String ACTION_UPDATE_TOPOLOGY_FINISHED = "im.tny.segvault.disturbances.action.topology.update.finished";
    public static final String EXTRA_UPDATE_TOPOLOGY_FINISHED = "im.tny.segvault.disturbances.extra.topology.update.finished";
    public static final String ACTION_UPDATE_TOPOLOGY_CANCELLED = "im.tny.segvault.disturbances.action.topology.update.cancelled";

    public static final String ACTION_TOPOLOGY_UPDATE_AVAILABLE = "im.tny.segvault.disturbances.action.topology.update.available";

    public static final String ACTION_LINE_STATUS_UPDATE_STARTED = "im.tny.segvault.disturbances.action.linestatus.update.started";
    public static final String ACTION_LINE_STATUS_UPDATE_SUCCESS = "im.tny.segvault.disturbances.action.linestatus.update.success";
    public static final String ACTION_LINE_STATUS_UPDATE_FAILED = "im.tny.segvault.disturbances.action.linestatus.update.failure";

    public static final String ACTION_CHECK_TOPOLOGY_UPDATES = "im.tny.segvault.disturbances.action.checkTopologyUpdates";

    public static class LocationJobCreator implements JobCreator {

        @Override
        public Job create(String tag) {
            switch (tag) {
                case UpdateTopologyJob.TAG:
                    return new UpdateTopologyJob();
                default:
                    return null;
            }
        }
    }

    public static class UpdateTopologyJob extends Job {
        public static final String TAG = "job_update_topology";

        @Override
        @NonNull
        protected Result onRunJob(Params params) {
            Intent intent = new Intent(getContext(), MainService.class).setAction(ACTION_CHECK_TOPOLOGY_UPDATES);
            getContext().startService(intent);
            return Result.SUCCESS;
        }

        public static void schedule() {
            schedule(true);
        }

        public static void schedule(boolean updateCurrent) {
            new JobRequest.Builder(UpdateTopologyJob.TAG)
                    .setExecutionWindow(TimeUnit.HOURS.toMillis(12), TimeUnit.HOURS.toMillis(36))
                    .setBackoffCriteria(TimeUnit.MINUTES.toMillis(30), JobRequest.BackoffPolicy.EXPONENTIAL)
                    .setRequiredNetworkType(JobRequest.NetworkType.UNMETERED)
                    .setPersisted(true)
                    .setUpdateCurrent(updateCurrent)
                    .build()
                    .schedule();
        }
    }

    private static final String ACTION_DISTURBANCE_NOTIFICATION = "im.tny.segvault.disturbances.action.notification.disturbance";

    private static final String EXTRA_DISTURBANCE_NETWORK = "im.tny.segvault.disturbances.extra.notification.disturbance.network";
    private static final String EXTRA_DISTURBANCE_LINE = "im.tny.segvault.disturbances.extra.notification.disturbance.line";
    private static final String EXTRA_DISTURBANCE_ID = "im.tny.segvault.disturbances.extra.notification.disturbance.id";
    private static final String EXTRA_DISTURBANCE_STATUS = "im.tny.segvault.disturbances.extra.notification.disturbance.status";
    private static final String EXTRA_DISTURBANCE_DOWNTIME = "im.tny.segvault.disturbances.extra.notification.disturbance.downtime";
    private static final String EXTRA_DISTURBANCE_MSGTIME = "im.tny.segvault.disturbances.extra.notification.disturbance.msgtime";

    public static void startForNotification(Context context, String network, String line,
                                            String id, String status, boolean downtime, long messageTime) {
        Intent intent = new Intent(context, MainService.class);
        intent.setAction(ACTION_DISTURBANCE_NOTIFICATION);
        intent.putExtra(EXTRA_DISTURBANCE_NETWORK, network);
        intent.putExtra(EXTRA_DISTURBANCE_LINE, line);
        intent.putExtra(EXTRA_DISTURBANCE_ID, id);
        intent.putExtra(EXTRA_DISTURBANCE_STATUS, status);
        intent.putExtra(EXTRA_DISTURBANCE_DOWNTIME, downtime);
        intent.putExtra(EXTRA_DISTURBANCE_MSGTIME, messageTime);
        context.startService(intent);
    }

    private void handleDisturbanceNotification(String network, String line,
                                               String id, String status, boolean downtime,
                                               long msgtime) {
        Log.d("MainService", "handleDisturbanceNotification");
        SharedPreferences sharedPref = getSharedPreferences("notifsettings", MODE_PRIVATE);
        Set<String> linePref = sharedPref.getStringSet("pref_notifs_lines", null);

        Map<String, LineStatus> statuses = getLineStatus();
        if (statuses.containsKey(line)) {
            LineStatus s = statuses.get(line);
            synchronized (lock) {
                if (downtime) {
                    lineStatuses.put(line, new LineStatus(s.line, new Date(msgtime)));
                } else {
                    lineStatuses.put(line, new LineStatus(s.line));
                }
                cacheLineStatus(lineStatuses);
            }
            Intent intent = new Intent(ACTION_LINE_STATUS_UPDATE_SUCCESS);
            LocalBroadcastManager bm = LocalBroadcastManager.getInstance(MainService.this);
            bm.sendBroadcast(intent);
        }

        if (linePref != null && !linePref.contains(line)) {
            // notifications disabled for this line
            return;
        }

        Network snetwork;
        synchronized (lock) {
            if (!networks.containsKey(network)) {
                return;
            }
            snetwork = networks.get(network);
        }
        Line sline = snetwork.getLine(line);
        if (sline == null) {
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_INITIAL_FRAGMENT, R.id.nav_disturbances);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT);

        String title = String.format(getString(R.string.notif_disturbance_title), sline.getName());

        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();
        bigTextStyle.setBigContentTitle(title);
        bigTextStyle.bigText(status);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
                .setStyle(bigTextStyle)
                .setSmallIcon(R.drawable.ic_disturbance_notif)
                .setColor(sline.getColor())
                .setContentTitle(title)
                .setContentText(status)
                .setAutoCancel(true)
                .setSound(Uri.parse(sharedPref.getString("pref_notifs_ringtone", "content://settings/system/notification_sound")))
                .setContentIntent(pendingIntent);

        if (sharedPref.getBoolean("pref_notifs_vibrate", false)) {
            notificationBuilder.setVibrate(new long[]{0, 100, 100, 150, 150, 200});
        } else {
            notificationBuilder.setVibrate(new long[]{0l});
        }

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notify(id.hashCode(), notificationBuilder.build());
    }

    private Notification buildRouteNotification(InNetworkState state) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_INITIAL_FRAGMENT, R.id.nav_home);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT);

        Stop curStop = state.getPath().getEndVertex();
        String title = curStop.getStation().getName();
        String status = getString(R.string.notif_route_waiting);
        if (state.getPath().getEdgeList().size() > 0) {
            Connection latest = state.getPath().getEdgeList().get(state.getPath().getEdgeList().size() - 1);
            Stop direction = curStop.getLine().getDirectionForConnection(latest);
            Stop next = curStop.getLine().getStopAfter(latest);
            if (direction != null && next != null) {
                status = String.format(getString(R.string.notif_route_next_station) + "\n", next.getStation().getName());
                status += String.format(getString(R.string.notif_route_direction), direction.getStation().getName());
            } else if (state.getLocation().vertexSet().size() == 0) {
                status = getString(R.string.notif_route_left_station);
            }
        } else if (state.getLocation().vertexSet().size() == 0) {
            status = getString(R.string.notif_route_left_station);
        }

        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();
        bigTextStyle.setBigContentTitle(curStop.getStation().getName());
        bigTextStyle.bigText(status);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
                .setStyle(bigTextStyle)
                .setSmallIcon(R.drawable.ic_trip_notif)
                .setColor(curStop.getLine().getColor())
                .setContentTitle(title)
                .setContentText(status.replace("\n", " | "))
                .setAutoCancel(false)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        return notificationBuilder.build();
    }

    private static final int ROUTE_NOTIFICATION_ID = -100;

    public class S2SLChangeListener implements S2LS.OnChangeListener {
        @Override
        public void onStateChanged(S2LS loc, S2LS.StateType state) {
            Log.d("onStateChanged", state.toString());

            stateTickHandler.removeCallbacksAndMessages(null);
            if (loc.getState().getPreferredTickIntervalMillis() != 0) {
                doTick(loc.getState());
            }

            SharedPreferences sharedPref = getSharedPreferences("settings", MODE_PRIVATE);
            boolean locationEnabled = sharedPref.getBoolean("pref_location_enable", true);

            if (loc.getState() instanceof InNetworkState) {
                wfc.setScanInterval(TimeUnit.SECONDS.toMillis(10));
                if (locationEnabled)
                    wfc.startScanning();
                final InNetworkState ins = (InNetworkState) loc.getState();
                ins.addLocationChangedListener(new InNetworkState.OnLocationChangedListener() {
                    @Override
                    public void onLocationChanged(InNetworkState state) {
                        Log.d("onLocationChanged", "Location changed");
                        startForeground(ROUTE_NOTIFICATION_ID, buildRouteNotification(ins));
                    }
                });
            } else if (loc.getState() instanceof NearNetworkState) {
                wfc.setScanInterval(TimeUnit.MINUTES.toMillis(1));
                if (locationEnabled)
                    wfc.startScanningIfWiFiEnabled();
                stopForeground(true);
            } else if (loc.getState() instanceof OffNetworkState) {
                // wfc.stopScanning(); // TODO only enable this when there are locators besides WiFi
                wfc.setScanInterval(TimeUnit.MINUTES.toMillis(1));
                if (locationEnabled)
                    wfc.startScanningIfWiFiEnabled();
                stopForeground(true);
            }
        }

        @Override
        public void onTripEnded(S2LS s2ls, InNetworkState.Path path) {
            Realm realm = Realm.getDefaultInstance();
            realm.beginTransaction();
            RealmList<StationUse> uses = new RealmList<>();
            int size = path.getEdgeList().size();
            if (size == 0) {
                StationUse use = new StationUse();
                use.setType(StationUse.UseType.VISIT);
                use.setStation(realm.where(RStation.class).equalTo("id", path.getStartVertex().getStation().getId()).findFirst());
                use.setEntryDate(path.getEntryExitTimes(0).first);
                use.setLeaveDate(path.getEntryExitTimes(0).second);
                uses.add(realm.copyToRealm(use));
            } else if (size == 1 && path.getEdgeList().get(0) instanceof Transfer) {
                Connection c = path.getEdgeList().get(0);
                StationUse use = new StationUse();
                use.setType(StationUse.UseType.VISIT);
                use.setSourceLine(c.getSource().getLine().getId());
                use.setTargetLine(c.getTarget().getLine().getId());
                use.setEntryDate(path.getEntryExitTimes(0).first);
                use.setLeaveDate(path.getEntryExitTimes(0).second);
                use.setStation(realm.where(RStation.class).equalTo("id", c.getSource().getStation().getId()).findFirst());
                uses.add(realm.copyToRealm(use));
            } else {
                int timeIdx = 0;
                StationUse startUse = new StationUse();
                startUse.setType(StationUse.UseType.NETWORK_ENTRY);
                startUse.setStation(realm.where(RStation.class).equalTo("id", path.getStartVertex().getStation().getId()).findFirst());
                startUse.setEntryDate(path.getEntryExitTimes(timeIdx).first);
                startUse.setLeaveDate(path.getEntryExitTimes(timeIdx).second);
                uses.add(realm.copyToRealm(startUse));

                int i = 1;
                timeIdx++;
                if (path.getEdgeList().get(0) instanceof Transfer) {
                    i = 2;
                    timeIdx++;
                }

                for (; i < size; i++) {
                    Connection c = path.getEdgeList().get(i);
                    StationUse use = new StationUse();
                    if (c instanceof Transfer) {
                        if (i == size - 1) break;
                        use.setType(StationUse.UseType.INTERCHANGE);
                        use.setSourceLine(c.getSource().getLine().getId());
                        use.setTargetLine(c.getTarget().getLine().getId());
                        use.setEntryDate(path.getEntryExitTimes(timeIdx).first);
                        timeIdx++;
                        use.setLeaveDate(path.getEntryExitTimes(timeIdx).second);
                        timeIdx++;
                        i++; // skip next station as then we'd have duplicate uses for the same station ID
                    } else {
                        use.setType(StationUse.UseType.GONE_THROUGH);
                        use.setEntryDate(path.getEntryExitTimes(timeIdx).first);
                        use.setLeaveDate(path.getEntryExitTimes(timeIdx).second);
                        timeIdx++;
                    }
                    use.setStation(realm.where(RStation.class).equalTo("id", c.getSource().getStation().getId()).findFirst());
                    uses.add(realm.copyToRealm(use));
                }

                StationUse endUse = new StationUse();
                endUse.setType(StationUse.UseType.NETWORK_EXIT);
                endUse.setStation(realm.where(RStation.class).equalTo("id", path.getEndVertex().getStation().getId()).findFirst());
                endUse.setEntryDate(path.getEntryExitTimes(timeIdx).first);
                endUse.setLeaveDate(path.getEntryExitTimes(timeIdx).second);
                uses.add(realm.copyToRealm(endUse));
            }
            Trip trip = new Trip();
            trip.setId(UUID.randomUUID().toString());
            trip.setPath(uses);
            realm.copyToRealm(trip);
            realm.commitTransaction();
        }

        private void doTick(final State state) {
            stateTickHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    doTick(state);
                    state.tick();
                }
            }, state.getPreferredTickIntervalMillis());
        }
    }

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final int KEEP_ALIVE = 1;

    private static final ThreadFactory yourFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(@NonNull Runnable r) {
            ThreadGroup group = new ThreadGroup("threadGroup");
            return new Thread(group, r, "LargeCallStackThread", 20000);
        }
    };

    private static final BlockingQueue<Runnable> sPoolWorkQueue =
            new LinkedBlockingQueue<Runnable>(128);

    public static final Executor LARGE_STACK_THREAD_POOL_EXECUTOR
            = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE,
            TimeUnit.SECONDS, sPoolWorkQueue, yourFactory);

    private SharedPreferences.OnSharedPreferenceChangeListener generalPrefsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (key.equals("pref_location_enable")) {
                if (prefs.getBoolean("pref_location_enable", true))
                    wfc.startScanningIfWiFiEnabled();
                else
                    wfc.stopScanning();
            }
        }
    };

}
