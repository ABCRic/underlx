package im.tny.segvault.disturbances;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * A fragment representing a list of Items.
 * <p/>
 * Activities containing this fragment MUST implement the {@link OnListFragmentInteractionListener}
 * interface.
 */
public class HomeLinesFragment extends Fragment {

    private static final String ARG_COLUMN_COUNT = "column-count";
    private int mColumnCount = 1;
    private OnListFragmentInteractionListener mListener;
    private RecyclerView recyclerView = null;
    private ProgressBar progressBar = null;
    private TextView updateInformationView = null;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public HomeLinesFragment() {
    }

    public static HomeLinesFragment newInstance(int columnCount) {
        HomeLinesFragment fragment = new HomeLinesFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_COLUMN_COUNT, columnCount);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() != null) {
            mColumnCount = getArguments().getInt(ARG_COLUMN_COUNT);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home_lines, container, false);

        // Set the adapter
        Context context = view.getContext();
        recyclerView = (RecyclerView) view.findViewById(R.id.list);
        if (mColumnCount <= 1) {
            recyclerView.setLayoutManager(new LinearLayoutManager(context));
        } else {
            recyclerView.setLayoutManager(new GridLayoutManager(context, mColumnCount));
        }
        recyclerView.setVisibility(View.GONE);
        progressBar = (ProgressBar) view.findViewById(R.id.loading_indicator);
        progressBar.setVisibility(View.VISIBLE);
        updateInformationView = (TextView) view.findViewById(R.id.update_information);

        // fix scroll fling. less than ideal, but apparently there's still no other solution
        recyclerView.setNestedScrollingEnabled(false);

        IntentFilter filter = new IntentFilter();
        filter.addAction(MainActivity.ACTION_MAIN_SERVICE_BOUND);
        filter.addAction(MainService.ACTION_UPDATE_TOPOLOGY_FINISHED);
        filter.addAction(LineStatusCache.ACTION_LINE_STATUS_UPDATE_STARTED);
        filter.addAction(LineStatusCache.ACTION_LINE_STATUS_UPDATE_SUCCESS);
        filter.addAction(LineStatusCache.ACTION_LINE_STATUS_UPDATE_FAILED);
        LocalBroadcastManager bm = LocalBroadcastManager.getInstance(context);
        bm.registerReceiver(mBroadcastReceiver, filter);
        redraw(context);
        return view;
    }


    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof OnListFragmentInteractionListener) {
            mListener = (OnListFragmentInteractionListener) context;
        } else {
            throw new RuntimeException(context.toString()
                    + " must implement OnListFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    private void redraw(Context context) {
        if (mListener == null || mListener.getLineStatusCache() == null) {
            return;
        }
        List<LineRecyclerViewAdapter.LineItem> items = new ArrayList<>();

        Date mostRecentUpdate = new Date();
        int count = 0;
        for (LineStatusCache.Status s : mListener.getLineStatusCache().getLineStatus().values()) {
            if (s.line == null) {
                continue;
            }
            if (s.down) {
                items.add(new LineRecyclerViewAdapter.LineItem(s.line, s.downSince));
            } else {
                items.add(new LineRecyclerViewAdapter.LineItem(s.line));
            }
            if (s.updated.getTime() < mostRecentUpdate.getTime()) {
                mostRecentUpdate = s.updated;
            }
            count++;
        }
        if (count == 0) {
            // no lines. probably still loading
            return;
        }

        Collections.sort(items, new Comparator<LineRecyclerViewAdapter.LineItem>() {
            @Override
            public int compare(LineRecyclerViewAdapter.LineItem lineItem, LineRecyclerViewAdapter.LineItem t1) {
                return lineItem.name.compareTo(t1.name);
            }
        });

        recyclerView.setAdapter(new LineRecyclerViewAdapter(items, mListener));
        recyclerView.invalidate();
        recyclerView.setVisibility(View.VISIBLE);

        if (new Date().getTime() - mostRecentUpdate.getTime() > java.util.concurrent.TimeUnit.MINUTES.toMillis(5)) {
            recyclerView.setAlpha(0.6f);
            updateInformationView.setTypeface(null, Typeface.BOLD);
        } else {
            recyclerView.setAlpha(1f);
            updateInformationView.setTypeface(null, Typeface.NORMAL);
        }
        updateInformationView.setText(String.format(getString(R.string.frag_lines_updated),
                DateUtils.getRelativeTimeSpanString(context, mostRecentUpdate.getTime(), true)));
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     * <p/>
     * See the Android Training lesson <a href=
     * "http://developer.android.com/training/basics/fragments/communicating.html"
     * >Communicating with Other Fragments</a> for more information.
     */
    public interface OnListFragmentInteractionListener {
        void onListFragmentInteraction(LineRecyclerViewAdapter.LineItem item);

        void onLinesFinishedRefreshing();

        LineStatusCache getLineStatusCache();
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getActivity() == null) {
                return;
            }
            switch (intent.getAction()) {
                case LineStatusCache.ACTION_LINE_STATUS_UPDATE_STARTED:
                    progressBar.setVisibility(View.VISIBLE);
                    break;
                case LineStatusCache.ACTION_LINE_STATUS_UPDATE_SUCCESS:
                case LineStatusCache.ACTION_LINE_STATUS_UPDATE_FAILED:
                    if (mListener != null) {
                        mListener.onLinesFinishedRefreshing();
                    }
                    progressBar.setVisibility(View.GONE);
                    // fallthrough
                case MainActivity.ACTION_MAIN_SERVICE_BOUND:
                    redraw(context);
                    break;
                case MainService.ACTION_UPDATE_TOPOLOGY_FINISHED:
                    break;
            }
        }
    };
}
